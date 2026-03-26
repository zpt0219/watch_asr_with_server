package com.adna.audiorecorderphone

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

class MainActivity : AppCompatActivity(), AudioServerService.UiCallbacks {

    private val tag = "audioRecorderPhone"
    private val permissionRequestCode = 111

    private var toastMessage: Toast? = null
    private lateinit var bluetoothAdapter: BluetoothAdapter
    
    private lateinit var textView: TextView
    private lateinit var transcriptTextView: TextView
    private lateinit var serverHostEditText: EditText
    private lateinit var serverPortEditText: EditText
    private lateinit var scanButton: Button
    private lateinit var serverButton: Button
    
    private var targetDevice: BluetoothDevice? = null

    private var audioServerService: AudioServerService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioServerService.LocalBinder
            audioServerService = binder.getService()
            audioServerService?.setUiCallbacks(this@MainActivity)
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioServerService?.setUiCallbacks(null)
            audioServerService = null
            isBound = false
        }
    }

    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = applicationContext.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        textView = findViewById(R.id.main_text_view)
        transcriptTextView = findViewById(R.id.transcript_text_view)
        serverHostEditText = findViewById(R.id.server_host_edit_text)
        serverPortEditText = findViewById(R.id.server_port_edit_text)
        scanButton = findViewById(R.id.scan_button)
        serverButton = findViewById(R.id.server_button)
        
        scanButton.setOnClickListener {
            if (ensurePermissionsReady()) {
                scanBluetoothConnection()
            }
        }

        serverButton.setOnClickListener {
            if (!ensurePermissionsReady()) {
                return@setOnClickListener
            }

            val endpoint = getTranslationServerEndpoint() ?: return@setOnClickListener

            val intent = Intent(this, AudioServerService::class.java).apply {
                putExtra("EXTRA_HOST", endpoint.first)
                putExtra("EXTRA_PORT", endpoint.second)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            updateStatus("Starting Background Server...")
            updateButtons()
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        updateButtons(false)
        setTranscript("Enter the translation server host and port, then receive audio.")
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, AudioServerService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        if (hasAllPermissions()) {
            updateButtons()
        } else {
            requestMissingPermissions()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            audioServerService?.setUiCallbacks(null)
            unbindService(serviceConnection)
            isBound = false
        }
    }

    @SuppressLint("MissingPermission")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == permissionRequestCode) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                updateButtons()
                if (hasAllPermissions() && bluetoothAdapter.isEnabled) {
                    scanBluetoothConnection()
                }
            } else {
                updateButtons()
                updateStatus("Required permissions are needed to operate.")
            }
        }
    }

    private fun updateButtons(isEnabled: Boolean = hasAllPermissions()) {
        val updateAction = {
            scanButton.isEnabled = isEnabled
            serverButton.isEnabled = isEnabled
        }
        runOnUiThread(updateAction)
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all { permission ->
            ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMissingPermissions() {
        val missingPermissions = buildList {
            requiredPermissions.forEach { permission ->
                if (ActivityCompat.checkSelfPermission(this@MainActivity, permission) != PackageManager.PERMISSION_GRANTED) {
                    add(permission)
                }
            }
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), permissionRequestCode)
        }
    }

    private fun ensurePermissionsReady(): Boolean {
        if (!hasAllPermissions()) {
            requestMissingPermissions()
            return false
        }

        if (!bluetoothAdapter.isEnabled) {
            showToastMessage("Turn Bluetooth on first")
            return false
        }

        return true
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            textView.text = message
        }
        Log.d(tag, message)
    }

    private fun getTranslationServerEndpoint(): Pair<String, Int>? {
        val host = serverHostEditText.text?.toString()?.trim().orEmpty()
        val port = serverPortEditText.text?.toString()?.trim()?.toIntOrNull()

        if (host.isEmpty()) {
            updateStatus("Enter the translation server host")
            return null
        }

        if (port == null || port !in 1..65535) {
            updateStatus("Enter a valid translation server port")
            return null
        }

        return host to port
    }

    private fun setTranscript(message: String) {
        runOnUiThread {
            transcriptTextView.text = message
        }
    }

    private fun appendTranscript(message: String) {
        runOnUiThread {
            val existing = transcriptTextView.text?.toString().orEmpty().trim()
            transcriptTextView.text = if (existing.isEmpty()) {
                message
            } else {
                "$existing\n\n$message"
            }
        }
    }

    private fun showToastMessage(message: String) {
        runOnUiThread {
            toastMessage?.cancel()
            toastMessage = Toast.makeText(this, message, Toast.LENGTH_SHORT)
            toastMessage?.show()
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    private fun scanBluetoothConnection() {
        val bondedDevices = bluetoothAdapter.bondedDevices
            ?.sortedBy { it.name?.lowercase(Locale.getDefault()) ?: it.address }
            .orEmpty()

        if (bondedDevices.isEmpty()) {
            targetDevice = null
            updateStatus("No paired phone found. Pair both phones first in Android Bluetooth settings.")
            updateButtons()
            return
        }

        if (bondedDevices.size == 1) {
            selectTargetDevice(bondedDevices.first())
            return
        }

        showDeviceSelectionDialog(bondedDevices)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun selectTargetDevice(device: BluetoothDevice) {
        targetDevice = device
        
        val summary = buildString {
            append("Selected device:\n")
            append(device.name ?: "Unknown device")
            append("\n")
            append(device.address)
        }
        updateStatus(summary)
        updateButtons()
        showToastMessage("Selected ${device.name ?: device.address}")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun showDeviceSelectionDialog(devices: List<BluetoothDevice>) {
        val labels = devices.map { device ->
            "${device.name ?: "Unknown device"}\n${device.address}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Choose paired phone")
            .setItems(labels) { _, which ->
                selectTargetDevice(devices[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- AudioServerService.UiCallbacks ---
    override fun onStatusUpdate(message: String) {
        updateStatus(message)
    }

    override fun onTranscriptSet(message: String) {
        setTranscript(message)
    }

    override fun onTranscriptAppended(message: String) {
        appendTranscript(message)
    }

    override fun onToast(message: String) {
        showToastMessage(message)
    }

    override fun onStateChanged() {
        updateButtons()
    }
}