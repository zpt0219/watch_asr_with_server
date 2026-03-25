package com.adna.audiorecorderphone

import android.Manifest // Used to access the names of standard Android runtime permissions
import android.annotation.SuppressLint // Used to suppress compiler permission warnings for Bluetooth
import android.bluetooth.BluetoothAdapter // Represents the actual local Bluetooth radio in the phone
import android.bluetooth.BluetoothDevice // Represents remote Bluetooth devices (the watches)
import android.bluetooth.BluetoothManager // Used to pull the BluetoothAdapter securely from the OS
import android.content.pm.PackageManager // Used to check if permissions are currently allowed
import android.os.Build // Used to natively check what version of Android this phone is running
import android.os.Bundle // Holds the "saved state" when an app activity boots up or gets restored
import android.util.Log // Standard logging module class
import android.widget.Button // Defines a normal UI button element
import android.widget.EditText // Defines a standard text input field element
import android.widget.TextView // Defines a standard text string element
import android.widget.Toast // Displays a quick popup status message bubble
import androidx.activity.enableEdgeToEdge // Modern Android UI call: stretches app to cover screen seamlessly
import androidx.annotation.RequiresPermission // Syntactic sugar instructing compiler that a method needs strong permissions
import androidx.appcompat.app.AlertDialog // Simple classic pop-up modal view
import androidx.appcompat.app.AppCompatActivity // The core foundation that all Android "Screens" are built on
import androidx.core.app.ActivityCompat // Helper library to request Permissions on older Android versions safely
import androidx.core.view.ViewCompat // Helper library to support Window overlap
import androidx.core.view.WindowInsetsCompat // Represents boundaries for system padding (Navbar/Statusbar)
import java.util.Locale // Region/Language tool
import java.util.UUID // Standard Unique Identifier 

/**
 * Main activity for the Phone application.
 *
 * Scopes out responsibilities specifically needed for the phone:
 * 1. Establishing a Bluetooth connection with the watch.
 * 2. Receiving microphone audio chunks over Bluetooth.
 * 3. Immediately forwarding those audio chunks to the configured TCP translation server.
 * 4. Displaying text transcripts received from the server.
 */
class MainActivity : AppCompatActivity(), BluetoothConnectionController.Callbacks {

    // Log tag name representing this app.
    private val tag = "audiorecorderphone"
    
    // The completely secure, random UUID we require both devices to use so they can talk securely natively.
    private val appUuid = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    private val serviceName = tag
    
    // Unique ID number for permission requests so we can track the system callback.
    private val permissionRequestCode = 111

    private var toastMessage: Toast? = null
    // Lazy initialized so we don't fetch it until onCreate natively generates it!
    private lateinit var bluetoothAdapter: BluetoothAdapter
    
    // UI Elements defined in the active XML layout file
    private lateinit var textView: TextView
    private lateinit var transcriptTextView: TextView
    private lateinit var serverHostEditText: EditText
    private lateinit var serverPortEditText: EditText
    private lateinit var scanButton: Button
    private lateinit var serverButton: Button
    
    // Core custom controllers handling networking
    private lateinit var connectionController: BluetoothConnectionController
    private lateinit var translationServerClient: TranslationServerClient
    
    // Tracks the specifically highlighted BluetoothDevice from the Pairing List
    private var targetDevice: BluetoothDevice? = null

    // Compute permissions to request depending on this phone's exact Android version natively!
    private val bluetoothPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            emptyArray() // Android 11 and lower granted automatically via Manifest!
        }

    // Ignore compiler warnings telling us to check permission. We check manually in ensureBluetoothReady!
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request Bluetooth Service hardware from the Operating System.
        val bluetoothManager = applicationContext.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        // Initialize our Bluetooth management logic using "this" class to answer the callbacks!
        connectionController = BluetoothConnectionController(
            bluetoothAdapter = bluetoothAdapter,
            logTag = tag,
            appUuid = appUuid,
            serviceName = serviceName,
            callbacks = this
        )

        // Expand screen edge-to-edge aesthetics
        enableEdgeToEdge()
        // Inject the R.layout.activity_main XML file straight into this Kotlin Activity
        setContentView(R.layout.activity_main)

        // Map XML Views uniquely to our defined variables via findViewById
        textView = findViewById(R.id.main_text_view)
        transcriptTextView = findViewById(R.id.transcript_text_view)
        serverHostEditText = findViewById(R.id.server_host_edit_text)
        serverPortEditText = findViewById(R.id.server_port_edit_text)
        scanButton = findViewById(R.id.scan_button)
        serverButton = findViewById(R.id.server_button)
        
        // Initialize the TCP Server client using "Callbacks" to push transcripts to the UI!
        translationServerClient = TranslationServerClient(
            logTag = tag,
            updateStatus = ::updateStatus,
            setTranscript = ::setTranscript,      // Function Reference Overwrite
            appendTranscript = ::appendTranscript,// Function Reference Add
            showToast = ::showToastMessage,
        )

        // Set action for clicking the Scan Button
        scanButton.setOnClickListener {
            if (ensureBluetoothReady()) {
                scanBluetoothConnection()
            }
        }

        // Set action for clicking the Start Server button
        serverButton.setOnClickListener {
            // First check if Bluetooth hardware is active and permitted
            if (!ensureBluetoothReady()) {
                return@setOnClickListener
            }

            // Immediately boot the Bluetooth listening thread!
            connectionController.startServer()
            updateStatus("Server started. On the other phone tap Scan then Start client.")
            updateButtons() // Refresh button availability visually
        }

        // Add padding buffers so standard Android status bars don't overlap our texts/buttons.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Initially lock buttons until permissions load correctly
        updateButtons(false)
        setTranscript("Enter the translation server host and port, then receive audio.")
    }

    // Called natively when app returns from background
    override fun onResume() {
        super.onResume()
        if (hasBluetoothPermissions()) {
            updateButtons()
        } else {
            // Immediately ask for system permission popup
            requestMissingPermissions()
        }
    }

    // Called exactly before app shuts down natively
    override fun onDestroy() {
        // Destroy sockets to prevent infinite thread leaking memory in background!
        connectionController.stopAll()
        translationServerClient.release()
        super.onDestroy()
    }

    // Android callback natively triggered the SECOND the user confirms/denies the permission popup!
    @SuppressLint("MissingPermission")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        // We only care about our specific request code (111)
        if (requestCode == permissionRequestCode) {
            // Verifies EVERY passed array element is "GRANTED"
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                updateButtons()
                if (hasBluetoothPermissions()) {
                    scanBluetoothConnection() // Auto-fetch device list since permission succeeded
                }
            } else {
                // Denied.
                updateButtons()
                updateStatus("Bluetooth permission is required to receive live audio")
            }
        }
    }

    /**
     * Enables/disables UI buttons based on permission state.
     * Always calls 'runOnUiThread' since a network thread might invoke this!
     */
    private fun updateButtons(isEnabled: Boolean = hasBluetoothPermissions()) {
        val updateAction = {
            scanButton.isEnabled = isEnabled
            serverButton.isEnabled = isEnabled
        }
        runOnUiThread(updateAction)
    }

    /**
     * Confirms that all our Bluetooth Permissions logic currently returns "GRANTED" natively.
     */
    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true // Android 11+ is safe implicitly
        }

        return bluetoothPermissions.all { permission ->
            ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Triggers the actual Android System Permission Modal Window.
     */
    private fun requestMissingPermissions() {
        val missingPermissions = buildList {
            bluetoothPermissions.forEach { permission ->
                // Check missing list precisely
                if (ActivityCompat.checkSelfPermission(this@MainActivity, permission) != PackageManager.PERMISSION_GRANTED) {
                    add(permission)
                }
            }
        }

        if (missingPermissions.isNotEmpty()) {
            // Push Array!
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), permissionRequestCode)
        }
    }

    /**
     * Double checks both App Permissions + Physical Bluetooth Switch being flipped!
     */
    private fun ensureBluetoothReady(): Boolean {
        if (!hasBluetoothPermissions()) {
            requestMissingPermissions() // Popups
            return false
        }

        // Checks physical hardware
        if (!bluetoothAdapter.isEnabled) {
            showToastMessage("Turn Bluetooth on first")
            return false
        }

        return true
    }

    /**
     * Updates the main helper text view safely on the UI Thread.
     */
    private fun updateStatus(message: String) {
        runOnUiThread {
            textView.text = message
        }
        Log.d(tag, message) // Save to Logcat
    }

    /**
     * Reads and validates the TCP server host/port from the UI fields.
     */
    private fun getTranslationServerEndpoint(): Pair<String, Int>? {
        // Read text, remove spaces, parse to native String
        val host = serverHostEditText.text?.toString()?.trim().orEmpty()
        // Try parsing string to Integer, return entirely null if failed
        val port = serverPortEditText.text?.toString()?.trim()?.toIntOrNull()

        // Validation error 1
        if (host.isEmpty()) {
            updateStatus("Enter the translation server host")
            return null
        }

        // Validation error 2 (Invalid Internet Ports)
        if (port == null || port !in 1..65535) {
            updateStatus("Enter a valid translation server port")
            return null
        }

        return host to port // Native Kotlin Pair<String, Int>
    }

    // --- UI Methods for Transcript Viewer ---

    /**
     * Entirely deletes all text from the Translation block and writes the new string.
     */
    private fun setTranscript(message: String) {
        runOnUiThread {
            transcriptTextView.text = message
        }
    }

    /**
     * Adds the message to the absolute bottom of the Translation block, maintaining scroll context.
     */
    private fun appendTranscript(message: String) {
        runOnUiThread {
            val existing = transcriptTextView.text?.toString().orEmpty().trim()
            // If it's already empty, essentially sets it. Else appends via template "\n\n".
            transcriptTextView.text = if (existing.isEmpty()) {
                message
            } else {
                "$existing\n\n$message"
            }
        }
    }

    /**
     * Shows a fast "Toast" bubble. Cancels old ones first to prevent a 10s queue of popups natively.
     */
    private fun showToastMessage(message: String) {
        runOnUiThread {
            toastMessage?.cancel()
            toastMessage = Toast.makeText(this, message, Toast.LENGTH_SHORT)
            toastMessage?.show()
        }
    }

    // --- Bluetooth Selection Dialog Logics ---

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    private fun scanBluetoothConnection() {
        // Find historically paired Bluetooth devices from the Android system natively
        val bondedDevices = bluetoothAdapter.bondedDevices
            ?.sortedBy { it.name?.lowercase(Locale.getDefault()) ?: it.address } // sort A-Z
            .orEmpty()

        if (bondedDevices.isEmpty()) {
            targetDevice = null
            updateStatus("No paired phone found. Pair both phones first in Android Bluetooth settings.")
            updateButtons()
            return
        }

        // QOL: Select automatically!
        if (bondedDevices.size == 1) {
            selectTargetDevice(bondedDevices.first())
            return
        }

        // Display picker screen!
        showDeviceSelectionDialog(bondedDevices)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun selectTargetDevice(device: BluetoothDevice) {
        targetDevice = device
        
        // Output device MAC address visually
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
        // Map native Bluetooth objects uniquely into Array of Strings
        val labels = devices.map { device ->
            "${device.name ?: "Unknown device"}\n${device.address}"
        }.toTypedArray()

        // Produce standard pop-up dialog!
        AlertDialog.Builder(this)
            .setTitle("Choose paired phone")
            .setItems(labels) { _, which ->
                selectTargetDevice(devices[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Bluetooth Connection Controller Callbacks ---

    override fun onConnected(message: String) {
        updateStatus(message)
        getTranslationServerEndpoint()?.let { (host, port) ->
            // The millisecond Bluetooth pairs, we aggressively boot a TCP connection to the Python Server!
            translationServerClient.connect(host, port)
        }
        updateButtons()
    }

    override fun onDisconnected(message: String) {
        // Flush final audio buffer to python Server so that last transcript isn't lost
        translationServerClient.flush()
        updateStatus("$message Waiting for final server transcript...")
        updateButtons()
    }

    /**
     * Called automatically when the watch device sends a raw chunk of audio data.
     * Implements the core relay logic: forwards this data exactly to the TCP translation server.
     */
    override fun onAudioPayloadReceived(payload: ByteArray, size: Int) {
        // Get target. If empty or invalid, drop the audio packet.
        val endpoint = getTranslationServerEndpoint() ?: return
        
        // Ship the exact bluetooth bytecode natively to the IP address!
        translationServerClient.sendAudioChunk(endpoint.first, endpoint.second, payload, size)
    }

    override fun onStateChanged() {
        updateButtons()
    }

    override fun showToast(message: String) {
        showToastMessage(message)
    }
}