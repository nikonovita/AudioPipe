package com.asdfg.soundmaster.adb

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.asdfg.soundmaster.shizuku.ShizukuBackend
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "ShellExecutor"

/**
 * Privileged command executor.
 *
 * Preferred backend: Shizuku UserService (works while Wireless Android Auto owns Wi-Fi).
 * Fallback backend: embedded self-ADB over 127.0.0.1.
 */
@RequiresApi(Build.VERSION_CODES.R)
class ShellExecutor(private val context: Context) {

    interface CommandResultListener {
        fun onCommandResult(output: String, done: Boolean) {}
        fun onCommandError(error: String) {}
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("adb_settings", Context.MODE_PRIVATE)
    }

    private val keyStore: AdbKeyStore by lazy {
        PreferenceAdbKeyStore(prefs)
    }

    private var adbKey: AdbKey = AdbKey(keyStore, "soundmaster@${Build.MODEL}")

    private var cachedPort: Int = -1
    private var cachedClient: AtomicReference<AdbClient?> = AtomicReference(null)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val shizuku = ShizukuBackend(context)

    fun initializeShizuku(onStateChanged: (() -> Unit)? = null) {
        shizuku.initialize(onStateChanged)
    }

    val isShizukuAvailable: Boolean
        get() = shizuku.isBinderAvailable

    val hasShizukuPermission: Boolean
        get() = shizuku.hasPermission

    val isShizukuReady: Boolean
        get() = shizuku.isReady

    fun requestShizukuPermission(): Boolean = shizuku.requestPermission()

    val backendName: String
        get() = when {
            isShizukuReady -> "Shizuku"
            cachedClient.get()?.isConnected == true -> "ADB"
            isShizukuAvailable && !hasShizukuPermission -> "Shizuku permission required"
            else -> "Disconnected"
        }

    /**
     * Import an existing private ADB key for fallback mode.
     */
    fun importKey(keyBytes: ByteArray) {
        adbKey.importPrivateKey(keyBytes)
        adbKey = AdbKey(keyStore, "soundmaster@${Build.MODEL}")
        disconnect()
        prefs.edit().putBoolean("adb_imported", true).apply()
    }

    val isSetUp: Boolean
        get() = isShizukuAvailable ||
            prefs.getBoolean("adb_paired", false) ||
            prefs.getBoolean("adb_imported", false)

    val isConnected: Boolean
        get() = isShizukuReady || cachedClient.get()?.isConnected == true

    /**
     * Discover wireless debugging port using mDNS for ADB fallback.
     */
    fun discoverPort(callback: (Int) -> Unit) {
        if (isShizukuReady) {
            callback(-1)
            return
        }

        val mdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
            if (port > 0) {
                cachedPort = port
                prefs.edit().putInt("last_port", port).apply()
                callback(port)
            }
        }
        mdns.start()

        val lastPort = prefs.getInt("last_port", -1)
        if (lastPort > 0) {
            cachedPort = lastPort
            callback(lastPort)
        }
    }

    fun getCachedPort(): Int {
        if (cachedPort > 0) return cachedPort
        return prefs.getInt("last_port", -1)
    }

    /**
     * Connect to embedded ADB fallback.
     */
    suspend fun connect(port: Int = cachedPort): Boolean {
        if (isShizukuReady) return true

        if (port <= 0) {
            Log.e(TAG, "Invalid port: $port")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val client = AdbClient("127.0.0.1", port, adbKey)
                client.connect()
                cachedClient.set(client)
                cachedPort = port
                prefs.edit().putInt("last_port", port).apply()
                true
            } catch (e: Exception) {
                Log.e(TAG, "ADB connection failed", e)
                false
            }
        }
    }

    fun disconnect() {
        cachedClient.getAndSet(null)?.close()
    }

    fun command(command: String, listener: CommandResultListener) {
        scope.launch {
            try {
                val output = executePreferred(command)
                withContext(Dispatchers.Main) {
                    listener.onCommandResult(output, true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command failed: $command", e)
                withContext(Dispatchers.Main) {
                    listener.onCommandError(e.message ?: "Unknown error")
                }
            }
        }
    }

    suspend fun commandSync(command: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                Result.success(executePreferred(command))
            } catch (e: Exception) {
                Log.e(TAG, "Command failed: $command", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun executePreferred(command: String): String {
        if (isShizukuReady) {
            return withContext(Dispatchers.IO) {
                shizuku.execute(command)
            }
        }

        var client = cachedClient.get()

        if (client == null || !client.isConnected) {
            if (cachedPort <= 0) {
                cachedPort = prefs.getInt("last_port", -1)
            }
            if (cachedPort > 0) {
                if (connect(cachedPort)) {
                    client = cachedClient.get()
                }
            }
        }

        if (client == null || !client.isConnected) {
            throw IllegalStateException(
                if (isShizukuAvailable && !hasShizukuPermission) {
                    "Grant AudioPipe access in Shizuku"
                } else {
                    "Neither Shizuku nor ADB is connected"
                }
            )
        }

        return client.shellCommand(command, null)
    }

    fun markAsPaired() {
        prefs.edit().putBoolean("adb_paired", true).apply()
    }

    fun clearPairing() {
        prefs.edit()
            .remove("adb_paired")
            .remove("last_port")
            .apply()
        disconnect()
    }

    companion object {
        @Volatile
        private var instance: ShellExecutor? = null

        fun getInstance(context: Context): ShellExecutor {
            return instance ?: synchronized(this) {
                instance ?: ShellExecutor(context.applicationContext).also { instance = it }
            }
        }
    }
}
