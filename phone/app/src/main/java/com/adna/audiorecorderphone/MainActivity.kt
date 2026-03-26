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

/**
 * The main user interface screen for the Phone app.
 * 
 * Responsible for:
 * 1. Requesting necessary Bluetooth and Notification permissions.
 * 2. Allowing the user to input the IP and Port of the remote Translation Server.
 * 3. Starting the background AudioServerService that does the actual networking.
 * 4. Displaying status updates and live transcribed text from the server.
 */
class MainActivity : AppCompatActivity() {

    // Tag for logcat messages
    private val tag = "audioRecorderPhone"
    // Arbitrary integer used to track our permission request callback
    private val permissionRequestCode = 111

    // Holds the currently visible standard Android popup message, so we can cancel it if a new one pops up
    private var toastMessage: Toast? = null
    
    // Gateway to the phone's physical Bluetooth hardware
    private lateinit var bluetoothAdapter: BluetoothAdapter
    
    // Core UI Components 
    private lateinit var textView: TextView // Top status text
    private lateinit var transcriptTextView: TextView // Bottom large scrollable text area for transcript
    private lateinit var serverHostEditText: EditText // Input for Server IP (e.g., 192.168.1.5)
    private lateinit var serverPortEditText: EditText // Input for Server Port (e.g., 5000)
    private lateinit var serverButton: Button // The button that starts the background Service
    
    // Unused in server-mode, but represents a specific watch if we wanted to dial OUT to it
    private var targetDevice: BluetoothDevice? = null

    // Reference to our background Service so we can pass UI updates back and forth
    private var audioServerService: AudioServerService? = null
    // Tracks if we are currently successfully "attached" to the background Service 
    private var isBound = false

    /**
     * An anonymous object implementing the UiCallbacks interface.
     * This keeps the callback methods private so they don't pollute MainActivity's public API.
     */
    private val uiCallbacks = object : AudioServerService.UiCallbacks {
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

    /**
     * Defines exactly what happens when we connect or disconnect from the Background Service.
     */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // We just connected to the background service! Save the reference to it.
            val binder = service as AudioServerService.LocalBinder
            audioServerService = binder.getService()
            // Tell the service: "Hey, send all your screen updates to our callback object"
            audioServerService?.setUiCallbacks(uiCallbacks)
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Service crashed or died
            audioServerService?.setUiCallbacks(null)
            audioServerService = null
            isBound = false
        }
    }

    /**
     * Dynamically builds the list of Android Android Permissions we need depending on the OS Version.
     */
    private val requiredPermissions: Array<String>
        get() = buildList {
            // Android 12+ (API 31) separates Bluetooth into Scan and Connect permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            // Android 13+ (API 33) requires explicit permission to show Notifications
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    /**
     * Called when the app screen is first launched.
     */
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize our access to the Bluetooth hardware
        val bluetoothManager = applicationContext.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        // Makes the app draw behind the top status bar and bottom gesture bar gracefully
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Find all our visual elements from the XML layout
        textView = findViewById(R.id.main_text_view)
        transcriptTextView = findViewById(R.id.transcript_text_view)
        serverHostEditText = findViewById(R.id.server_host_edit_text)
        serverPortEditText = findViewById(R.id.server_port_edit_text)
        serverButton = findViewById(R.id.server_button)

        // Start Server Button: This is the main button the user clicks!
        serverButton.setOnClickListener {
            if (!ensurePermissionsReady()) {
                return@setOnClickListener
            }

            // Verify the user typed a valid IP and Port before trying to connect
            val endpoint = getTranslationServerEndpoint() ?: return@setOnClickListener

            // Create an Intent containing the IP and Port to pass to the Background Service
            val intent = Intent(this, AudioServerService::class.java).apply {
                putExtra("EXTRA_HOST", endpoint.first)
                putExtra("EXTRA_PORT", endpoint.second)
            }

            // Start the service! We use Foreground Service to keep it alive even if the user minimizes the app.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            updateStatus("Starting Background Server...")
            updateButtons()
        }

        // Apply visual padding so our UI doesn't hide behind system bars
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Initial setup
        updateButtons(false)
        setTranscript("Enter the translation server host and port, then receive audio.")
    }

    /**
     * Called when the screen becomes visible (even if partially obscured)
     */
    override fun onStart() {
        super.onStart()
        // If the background service is running, attach ourselves to it so we can see its updates!
        val intent = Intent(this, AudioServerService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Called when the screen is fully active and interactable.
     * We constantly double check permissions here in case the user revoked them in Settings.
     */
    override fun onResume() {
        super.onResume()
        if (hasAllPermissions()) {
            updateButtons()
        } else {
            requestMissingPermissions()
        }
    }

    /**
     * Called when the screen is no longer visible (minimized, or screen locked).
     */
    override fun onStop() {
        super.onStop()
        // We are backing out of the app, so detatch from the background service so we don't leak memory.
        if (isBound) {
            audioServerService?.setUiCallbacks(null)
            unbindService(serviceConnection)
            isBound = false
        }
    }

    /**
     * Triggered automatically by Android when the user clicks "Allow" or "Deny" on a permission popup.
     */
    @SuppressLint("MissingPermission")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == permissionRequestCode) {
            // Check if every single requested permission was successfully GRANTED.
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                updateButtons()
                // If they just granted it and bluetooth is ON, immediately prompt the scan!
                if (hasAllPermissions() && bluetoothAdapter.isEnabled) {
                    scanBluetoothConnection()
                }
            } else {
                updateButtons()
                updateStatus("Required permissions are needed to operate.")
            }
        }
    }

    /**
     * Safely updates the clickable state of the main Buttons strictly on the UI thread.
     */
    private fun updateButtons(isEnabled: Boolean = hasAllPermissions()) {
        val updateAction = {
            serverButton.isEnabled = isEnabled
        }
        runOnUiThread(updateAction)
    }

    /**
     * Boolean check to see if we have 100% of the permissions we need.
     */
    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all { permission ->
            ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Triggers the Android system prompts asking the user to Grant our required permissions.
     */
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

    /**
     * Master safety check used before allowing any network/bluetooth activity.
     */
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

    /**
     * Helper to safely change the top title text from any background thread.
     */
    private fun updateStatus(message: String) {
        runOnUiThread {
            textView.text = message
        }
        Log.d(tag, message)
    }

    /**
     * Reads the two text boxes, validates them, and returns them as a Pair(IP, Port).
     * Returns null and shows an error if they typed something invalid.
     */
    private fun getTranslationServerEndpoint(): Pair<String, Int>? {
        val host = serverHostEditText.text?.toString()?.trim().orEmpty()
        val port = serverPortEditText.text?.toString()?.trim()?.toIntOrNull()

        if (host.isEmpty()) {
            updateStatus("Enter the translation server host")
            return null
        }

        // Ports strictly range from 1 to 65535 on standard internet connections
        if (port == null || port !in 1..65535) {
            updateStatus("Enter a valid translation server port")
            return null
        }

        return host to port
    }

    /**
     * Completely wipes out the transcript box and replaces it with the specified text.
     */
    private fun setTranscript(message: String) {
        runOnUiThread {
            transcriptTextView.text = message
        }
    }

    /**
     * Adds the specified text to the BOTTOM of the existing transcript box seamlessly.
     */
    private fun appendTranscript(message: String) {
        runOnUiThread {
            val existing = transcriptTextView.text?.toString().orEmpty().trim()
            transcriptTextView.text = if (existing.isEmpty()) {
                message
            } else {
                "$existing\n\n$message" // Double newline separates transcript chunks cleanly
            }
        }
    }

    /**
     * Properly shows a fast pop-up bubble, cancelling any existing bubbles first so they don't lag behind.
     */
    private fun showToastMessage(message: String) {
        runOnUiThread {
            toastMessage?.cancel() // Clear old toast 
            toastMessage = Toast.makeText(this, message, Toast.LENGTH_SHORT) // Build new toast
            toastMessage?.show()
        }
    }

    /**
     * Legacy connection mechanism: Presents a list of previously paired bluetooth devices
     * and allows the user to click one to actively dial out to it.
     */
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

        // If you only own ONE paired bluetooth device... just auto-select it!
        if (bondedDevices.size == 1) {
            selectTargetDevice(bondedDevices.first())
            return
        }

        showDeviceSelectionDialog(bondedDevices)
    }

    /**
     * Saves our target device memory so we know who to talk to if we click connect.
     */
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

    /**
     * Pops an Android Dialog list forcing the user to pick which watch they want from a list.
     */
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
}