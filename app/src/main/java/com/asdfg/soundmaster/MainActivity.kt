package com.asdfg.soundmaster

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.asdfg.soundmaster.adb.ShellExecutor
import com.asdfg.soundmaster.audio.SoundMasterService
import com.asdfg.soundmaster.audio.AudioOutputPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@RequiresApi(Build.VERSION_CODES.R)
class MainActivity : AppCompatActivity() {

    private lateinit var shellExecutor: ShellExecutor
    private lateinit var audioManager: AudioManager

    // UI elements
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var importKeyButton: Button
    private lateinit var appSpinner: Spinner
    private lateinit var appSearch: EditText
    private lateinit var youtubeMusicButton: Button
    private lateinit var youtubeButton: Button
    private lateinit var spotifyButton: Button
    private lateinit var outputSpinner: Spinner
    private lateinit var bluetoothSettingsButton: Button
    private lateinit var volumeSeekBar: SeekBar
    private lateinit var volumeValue: TextView
    private lateinit var balanceSeekBar: SeekBar
    private lateinit var balanceValue: TextView
    private lateinit var startStopButton: Button
    private lateinit var serviceStatus: TextView

    private var installedApps: List<ApplicationInfo> = emptyList()
    private var filteredApps: List<ApplicationInfo> = emptyList()
    private var audioOutputs: List<AudioDeviceInfo> = emptyList()
    private var selectedApp: String? = null
    private var selectedOutput: AudioDeviceInfo? = null
    private var currentVolume = 100f
    private var currentBalance = 0f
    private var suppressOutputSelection = false
    private val preferences by lazy { getSharedPreferences("audio_pipe", Context.MODE_PRIVATE) }
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            runOnUiThread { loadAudioOutputs() }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            runOnUiThread { loadAudioOutputs() }
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            SoundMasterService.projectionData = result.data
            startSoundMaster()
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val importKeyLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bytes = inputStream.readBytes()
                    // Basic validation: Private key usually starts with -----BEGIN PRIVATE KEY-----
                    // or involves some binary structure. We'll trust the user for now.
                    if (bytes.isNotEmpty()) {
                        shellExecutor.importKey(bytes)
                        Toast.makeText(this, "Key imported! Connecting...", Toast.LENGTH_SHORT).show()
                        updateAdbStatus(false) // Reset status
                        
                        // Trigger immediate connection attempt
                        lifecycleScope.launch(Dispatchers.IO) {
                            shellExecutor.discoverPort { port ->
                                if (port > 0) {
                                    lifecycleScope.launch {
                                        val connected = shellExecutor.connect(port)
                                        withContext(Dispatchers.Main) {
                                            updateAdbStatus(connected)
                                            if (connected) {
                                                Toast.makeText(this@MainActivity, "Connected!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(this@MainActivity, "Connection failed", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Toast.makeText(this, "Empty file selected", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        shellExecutor = ShellExecutor.getInstance(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        createNotificationChannel()
        bindViews()
        setupListeners()

        shellExecutor.initializeShizuku {
            runOnUiThread { checkAdbStatus() }
        }

        loadApps()
        loadAudioOutputs()
        checkAdbStatus()
        requestPermissions()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    private fun bindViews() {
        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)
        importKeyButton = findViewById(R.id.importKeyButton)
        
        appSpinner = findViewById(R.id.appSpinner)
        appSearch = findViewById(R.id.appSearch)
        youtubeMusicButton = findViewById(R.id.youtubeMusicButton)
        youtubeButton = findViewById(R.id.youtubeButton)
        spotifyButton = findViewById(R.id.spotifyButton)
        outputSpinner = findViewById(R.id.outputSpinner)
        bluetoothSettingsButton = findViewById(R.id.bluetoothSettingsButton)
        volumeSeekBar = findViewById(R.id.volumeSeekBar)
        volumeValue = findViewById(R.id.volumeValue)
        balanceSeekBar = findViewById(R.id.balanceSeekBar)
        balanceValue = findViewById(R.id.balanceValue)
        startStopButton = findViewById(R.id.startStopButton)
        serviceStatus = findViewById(R.id.serviceStatus)
    }

    private fun setupListeners() {
        connectButton.setOnClickListener {
            if (shellExecutor.isShizukuAvailable && !shellExecutor.hasShizukuPermission) {
                if (!shellExecutor.requestShizukuPermission()) {
                    Toast.makeText(
                        this,
                        "Open Shizuku and make sure its service is running",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                Toast.makeText(this, "Retrying privileged connection...", Toast.LENGTH_SHORT).show()
                checkAdbStatus()
            }
        }

        importKeyButton.setOnClickListener {
            importKeyLauncher.launch(arrayOf("*/*"))
        }

        appSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
             override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                 val newApp = filteredApps.getOrNull(position)?.packageName
                 if (selectedApp != newApp) {
                     selectedApp = newApp
                     if (SoundMasterService.running) {
                         Toast.makeText(this@MainActivity, "Stop service to change app", Toast.LENGTH_SHORT).show()
                     }
                 }
             }
             override fun onNothingSelected(parent: AdapterView<*>?) {
                 selectedApp = null
             }
        }

        appSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        youtubeMusicButton.setOnClickListener { selectApp("com.google.android.apps.youtube.music") }
        youtubeButton.setOnClickListener { selectApp("com.google.android.youtube") }
        spotifyButton.setOnClickListener { selectApp("com.spotify.music") }

        bluetoothSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }

        outputSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newOutput = audioOutputs.getOrNull(position)
                if (suppressOutputSelection) {
                    selectedOutput = newOutput
                    return
                }
                if (selectedOutput != newOutput) {
                    val oldOutputId = selectedOutput?.id ?: -1
                    selectedOutput = newOutput
                    newOutput?.let { savePreferredOutput(it) }
                    
                    if (SoundMasterService.running) {
                        selectedApp?.let { pkg ->
                            val service = getService()
                            service?.packageThreads?.get(pkg)?.let { thread ->
                                val success = thread.switchOutputDevice(oldOutputId, newOutput)
                                if (success) {
                                    Toast.makeText(this@MainActivity, "Switched to ${newOutput?.productName}", Toast.LENGTH_SHORT).show()
                                    updateServiceUI(true)
                                }
                            }
                        }
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedOutput = null
            }
        }

        volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentVolume = progress.toFloat()
                volumeValue.text = "$progress%"
                if (SoundMasterService.running) {
                    selectedApp?.let { pkg ->
                        getService()?.packageThreads?.get(pkg)?.setVolume(selectedOutput?.id ?: -1, currentVolume)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        balanceSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentBalance = (progress - 100).toFloat()
                balanceValue.text = currentBalance.toInt().toString()
                if (SoundMasterService.running) {
                    selectedApp?.let { pkg ->
                        getService()?.packageThreads?.get(pkg)?.setBalance(selectedOutput?.id ?: -1, currentBalance)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        startStopButton.setOnClickListener {
            if (SoundMasterService.running) {
                stopService(Intent(this, SoundMasterService::class.java))
                updateServiceUI(false)
            } else {
                if (selectedApp == null) {
                    Toast.makeText(this, R.string.no_apps_selected, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (!shellExecutor.isConnected) {
                    if (shellExecutor.isShizukuAvailable && !shellExecutor.hasShizukuPermission) {
                        Toast.makeText(this, "Grant AudioPipe access in Shizuku", Toast.LENGTH_SHORT).show()
                        shellExecutor.requestShizukuPermission()
                        return@setOnClickListener
                    }

                    Toast.makeText(this, "Connecting to privileged backend...", Toast.LENGTH_SHORT).show()
                    statusText.text = "Connecting..."
                    
                    lifecycleScope.launch {
                        val port = shellExecutor.getCachedPort()
                        if (port > 0 && shellExecutor.connect(port)) {
                            updateAdbStatus(true)
                            requestMediaProjection()
                        } else {
                            shellExecutor.discoverPort { newPort ->
                                if (newPort > 0) {
                                    lifecycleScope.launch {
                                        if (shellExecutor.connect(newPort)) {
                                            withContext(Dispatchers.Main) {
                                                updateAdbStatus(true)
                                                requestMediaProjection()
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@MainActivity, "Failed to connect to ADB", Toast.LENGTH_SHORT).show()
                                                updateAdbStatus(false)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return@setOnClickListener
                }

                requestMediaProjection()
            }
        }
    }

    private fun checkAdbStatus() {
        if (shellExecutor.isShizukuReady) {
            updateAdbStatus(true)
            return
        }

        if (shellExecutor.isShizukuAvailable && !shellExecutor.hasShizukuPermission) {
            updateAdbStatus(false)
            return
        }

        if (shellExecutor.isSetUp) {
            lifecycleScope.launch {
                shellExecutor.discoverPort { port ->
                    if (shellExecutor.isShizukuReady) {
                        runOnUiThread { updateAdbStatus(true) }
                    } else if (port > 0) {
                        lifecycleScope.launch {
                            val connected = shellExecutor.connect(port)
                            withContext(Dispatchers.Main) {
                                updateAdbStatus(connected)
                            }
                        }
                    } else {
                        runOnUiThread { updateAdbStatus(false) }
                    }
                }
            }
        } else {
            updateAdbStatus(false)
        }
    }

    private fun updateAdbStatus(connected: Boolean) {
        if (shellExecutor.isShizukuAvailable && !shellExecutor.hasShizukuPermission) {
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_red)
            statusText.text = "Shizuku permission required"
            connectButton.text = "Grant"
        } else if (connected) {
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_green)
            statusText.text = "Connected via ${shellExecutor.backendName}"
            connectButton.text = shellExecutor.backendName
        } else {
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_red)
            statusText.text = getString(R.string.disconnected)
            connectButton.text = "Setup"
        }
    }

    private fun loadApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }

            withContext(Dispatchers.Main) {
                filterApps(appSearch.text?.toString().orEmpty())
            }
        }
    }

    private fun filterApps(query: String) {
        if (!::appSpinner.isInitialized) return
        val normalized = query.trim().lowercase()
        filteredApps = installedApps.filter { app ->
            val label = packageManager.getApplicationLabel(app).toString()
            normalized.isEmpty() || label.lowercase().contains(normalized) ||
                app.packageName.lowercase().contains(normalized)
        }

        val rows = filteredApps.map { app ->
            "${packageManager.getApplicationLabel(app)}\n${app.packageName}"
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, rows).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        appSpinner.adapter = adapter

        val selectedIndex = filteredApps.indexOfFirst { it.packageName == selectedApp }
        when {
            selectedIndex >= 0 -> appSpinner.setSelection(selectedIndex)
            filteredApps.isNotEmpty() -> appSpinner.setSelection(0)
            else -> selectedApp = null
        }
    }

    private fun selectApp(packageName: String) {
        val installed = installedApps.any { it.packageName == packageName }
        if (!installed) {
            Toast.makeText(this, "$packageName is not installed", Toast.LENGTH_SHORT).show()
            return
        }
        appSearch.setText(packageName)
        val index = filteredApps.indexOfFirst { it.packageName == packageName }
        if (index >= 0) appSpinner.setSelection(index)
    }

    private fun loadAudioOutputs() {
        audioOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }

        val outputNames = audioOutputs.map { "${it.productName ?: getDeviceTypeName(it.type)}" }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, outputNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        suppressOutputSelection = true
        outputSpinner.adapter = adapter

        val saved = loadPreferredOutput()
        val preferredIndex = saved?.let { preference -> audioOutputs.indexOfFirst(preference::matches) } ?: -1
        val fallbackIndex = if (saved == null) {
            audioOutputs.indexOfFirst { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        } else {
            -1
        }
        val index = if (preferredIndex >= 0) preferredIndex else fallbackIndex
        if (index >= 0) outputSpinner.setSelection(index)

        val savedBluetoothMissing = saved?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && preferredIndex < 0
        bluetoothSettingsButton.visibility = if (savedBluetoothMissing) View.VISIBLE else View.GONE
        if (savedBluetoothMissing) {
            serviceStatus.text = getString(R.string.bluetooth_disconnected, saved?.productName.orEmpty())
        }
        outputSpinner.post {
            selectedOutput = audioOutputs.getOrNull(index)
            selectedOutput?.let { if (saved == null) savePreferredOutput(it) }
            suppressOutputSelection = false
        }
    }

    private fun savePreferredOutput(device: AudioDeviceInfo) {
        val preference = AudioOutputPreference.from(device)
        preferences.edit()
            .putInt("preferred_output_type", preference.type)
            .putString("preferred_output_name", preference.productName)
            .apply()
    }

    private fun loadPreferredOutput(): AudioOutputPreference? {
        val type = preferences.getInt("preferred_output_type", -1)
        val name = preferences.getString("preferred_output_name", null) ?: return null
        return if (type >= 0) AudioOutputPreference(type, name) else null
    }

    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            else -> "Unknown"
        }
    }

    private fun requestMediaProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startSoundMaster() {
        val intent = Intent(this, SoundMasterService::class.java).apply {
            putExtra("packages", arrayOf(selectedApp))
            putExtra("devices", intArrayOf(selectedOutput?.id ?: -1))
            putExtra("volumes", floatArrayOf(currentVolume))
            val preference = selectedOutput?.let { AudioOutputPreference.from(it) } ?: loadPreferredOutput()
            putExtra(SoundMasterService.EXTRA_OUTPUT_TYPE, preference?.type ?: -1)
            putExtra(SoundMasterService.EXTRA_OUTPUT_NAME, preference?.productName)
        }
        startForegroundService(intent)
        updateServiceUI(true)
    }

    private fun updateServiceUI(running: Boolean) {
        if (running) {
            startStopButton.text = getString(R.string.stop)
            serviceStatus.text = "Routing ${selectedApp} → ${selectedOutput?.productName ?: "Default"}"
        } else {
            startStopButton.text = getString(R.string.start)
            serviceStatus.text = ""
        }
    }

    private fun getService(): SoundMasterService? {
        return SoundMasterService.instance
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            SoundMasterService.NOTIFICATION_CHANNEL,
            "SoundMaster Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )
        val needed = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceUI(SoundMasterService.running)
        loadAudioOutputs() 
    }

    override fun onDestroy() {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        super.onDestroy()
    }
}
