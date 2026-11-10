package com.asdfg.soundmaster.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import rikka.shizuku.Shizuku

class ShizukuBackend(private val context: Context) {

    private val remote = AtomicReference<IPrivilegedShell?>(null)
    private var initialized = false
    private var binding = false
    private var stateChanged: (() -> Unit)? = null

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context, PrivilegedShellService::class.java)
        )
            .processNameSuffix("audiopipe_shell")
            .tag("audiopipe-shell")
            .version(1)
            .daemon(true)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote.set(IPrivilegedShell.Stub.asInterface(service))
            binding = false
            Log.i(TAG, "Shizuku UserService connected")
            stateChanged?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote.set(null)
            binding = false
            Log.w(TAG, "Shizuku UserService disconnected")
            stateChanged?.invoke()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        if (hasPermission) {
            bindIfPermitted()
        } else {
            requestPermission()
        }
        stateChanged?.invoke()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        remote.set(null)
        binding = false
        Log.w(TAG, "Shizuku binder died")
        stateChanged?.invoke()
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
                bindIfPermitted()
            }
            stateChanged?.invoke()
        }

    fun initialize(onStateChanged: (() -> Unit)? = null) {
        stateChanged = onStateChanged
        if (initialized) {
            bindIfPermitted()
            stateChanged?.invoke()
            return
        }

        initialized = true
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        if (Shizuku.pingBinder()) {
            if (hasPermission) {
                bindIfPermitted()
            } else {
                requestPermission()
            }
        }
    }

    val isBinderAvailable: Boolean
        get() = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }

    val hasPermission: Boolean
        get() = try {
            isBinderAvailable &&
                !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }

    val isReady: Boolean
        get() = remote.get() != null && hasPermission

    fun requestPermission(): Boolean {
        if (!isBinderAvailable) return false
        if (hasPermission) {
            bindIfPermitted()
            return true
        }

        return try {
            if (!Shizuku.isPreV11() && !Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(REQUEST_CODE)
                true
            } else {
                false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Unable to request Shizuku permission", e)
            false
        }
    }

    fun execute(command: String): String {
        val service = remote.get() ?: throw IllegalStateException("Shizuku service is not connected")
        return service.exec(command)
    }

    private fun bindIfPermitted() {
        if (!hasPermission || remote.get() != null || binding) return

        try {
            binding = true
            Shizuku.bindUserService(serviceArgs, connection)
        } catch (e: Throwable) {
            binding = false
            Log.e(TAG, "Unable to bind Shizuku UserService", e)
        }
    }

    companion object {
        private const val TAG = "ShizukuBackend"
        private const val REQUEST_CODE = 1001
    }
}
