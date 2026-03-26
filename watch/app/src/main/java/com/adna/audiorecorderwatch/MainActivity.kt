package com.adna.audiorecorderwatch

import android.Manifest // Used to access the names of standard Android permissions
import android.annotation.SuppressLint // Used to suppress compiler warnings
import android.bluetooth.BluetoothAdapter // Represents the local device's Bluetooth radio
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice // Represents a remote Bluetooth device (the phone)
import android.bluetooth.BluetoothManager // Used to obtain the BluetoothAdapter
import android.content.pm.PackageManager // Used to check if permissions are granted or denied
import android.os.Build // Used to check the Android OS version running on the watch
import android.os.Bundle // Holds the state of the app when it starts up or resumes
import android.util.Log // Used for printing debug logs to the developers console
import android.widget.Button // A standard clickable UI button
import android.widget.TextView // A standard UI text element
import android.widget.Toast // Small popup notification alerts at the bottom of the screen
import androidx.activity.enableEdgeToEdge // Extends the app UI underneath the system status bar
import androidx.annotation.RequiresPermission // A compiler hint forcing you to check permissions before calling a function
import androidx.appcompat.app.AlertDialog // A modal dialog that pops up in the middle of the screen
import androidx.appcompat.app.AppCompatActivity // The base class for modern Android screens
import androidx.core.app.ActivityCompat // Helper for requesting runtime permissions seamlessly backwards
import androidx.core.view.ViewCompat // Helper for handling window overlays smoothly backwards
import androidx.core.view.WindowInsetsCompat // Represents padding needed to avoid system bars
import java.util.Locale // Represents language/region settings
import java.util.UUID // Universally Unique Identifier

/**
 * Main activity for the Watch application.
 *
 * Scopes out the responsibilities specifically needed for the watch:
 * 1. Establishing a Bluetooth connection (as client or server) with the phone.
 * 2. Starting/stopping the device microphone.
 * 3. Piping microphone audio data exclusively to the Bluetooth stream.
 */
class MainActivity : AppCompatActivity(), BluetoothConnectionController.Callbacks {

    // A static tag used so we know which class generated a log in Logcat.
    private val tag = "AudioRecorderWatch"
    
    // The completely secure, random UUID we require both devices to use so they can talk securely natively.
    private val appUuid = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    
    // Name broadcasted when the watch starts acting as a Bluetooth Server.
    private val serviceName = tag
    
    // An arbitrary integer used to identify the result of our permission request popup later in code.
    private val permissionRequestCode = 1

    // Holds a reference to the active toast so we can cancel it if a new one pops up suddenly.
    private var toastMessage: Toast? = null
    
    // 'lateinit' means we promise to initialize these variables later in onCreate() before we ever use them, avoiding null checks.
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var textView: TextView
    private lateinit var clientButton: Button
    private lateinit var recordingButton: Button
    
    // The managers we built to handle our background threading and networking.
    private lateinit var bluetoothConnectionController: BluetoothConnectionController
    private lateinit var audioStreamingController: AudioStreamingController
    
    // Holds the currently selected phone you want the watch to talk to.
    private var targetDevice: BluetoothDevice? = null

    // Determine the correct Bluetooth Permissions required based on Android SDK level.
    // Android 12 (S) and higher completely changed how Bluetooth permissions work!
    private val bluetoothPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ requires specific granular permissions to connect and scan.
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            // Android 11 and lower implicitly granted these permissions via Manifest install properties.
            emptyArray()
        }

    @SuppressLint("MissingPermission") // We actively request permissions manually so we suppress the compiler complaining.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Fetch the hardware Bluetooth Manager from the Android Operating System
        val bluetoothManager = applicationContext.getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        // 2. Initialize our custom connection controller class logic.
        bluetoothConnectionController = BluetoothConnectionController(
            bluetoothAdapter = bluetoothAdapter,
            logTag = tag,
            appUuid = appUuid,
            serviceName = serviceName,
            callbacks = this // "this" refers to this MainActivity (since we implement Callbacks interface)
        )
        
        // 3. Initialize our custom audio streaming logic.
        audioStreamingController = AudioStreamingController(
            logTag = tag,
            // We pass in references (functions) from MainActivity to let the controller update UI seamlessly!
            updateStatus = ::updateStatus,
            showToast = ::showToastMessage,
            hasActiveConnection = { bluetoothConnectionController.hasActiveConnection() },
            // Direct callback pipe connecting Audio -> Bluetooth Socket.
            sendAudioChunk = { bytes, size -> bluetoothConnectionController.sendAudioChunk(bytes, size) },
            onStreamingStateChanged = ::updateButtons
        )

        // 4. Force the app interface to stretch to all edges of the watch face.
        enableEdgeToEdge()
        // 5. Connect the layout XML code (R.layout.activity_main) to this Kotlin screen code!
        setContentView(R.layout.activity_main)

        // 6. Connect our Kotlin variables to the actual physical UI buttons defined in the XML.
        textView = findViewById(R.id.main_text_view)
        clientButton = findViewById(R.id.client_button)
        recordingButton = findViewById(R.id.send_data_button)


        // 7. Define what happens when we physically tap the "Connect" button.
        clientButton.setOnClickListener {
            prepareForRecording()
        }

        // 8. Define what happens when we tap the "Stream Recording" button.
        recordingButton.setOnClickListener {
            if (audioStreamingController.isStreamingAudio) {
                // If it's already recording natively, shut it down.
                audioStreamingController.stopStreaming("Audio recording stopped")
            } else if (ensureAudioStreamingReady()) {
                // Otherwise, verify mic permissions, check the socket is alive, and fire off the recording thread!
                audioStreamingController.startStreaming()
            }
        }

        // 9. Protect the views from overlapping the physical watch bezels or system status bars.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets // Returns the remaining insets.
        }

        // Apply a grey-out to the buttons unless bluetooth is confirmed working.
        updateButtons(false)
    }

    // Called automatically whenever the Watch screen wakes up or gets resumed.
    override fun onResume() {
        super.onResume()
        // Every time we wake up, re-check our live permissions.
        if (hasBluetoothPermissions()) {
            updateButtons()
        } else {
            // Triggers the system permission popup.
            requestMissingPermissions()
        }
    }

    // Called absolutely last right before the OS completely destroys this app screen memory.
    override fun onDestroy() {
        // We MUST close threads or else the background thread keeps running "zombie" processes forever!
        audioStreamingController.releaseAll()
        bluetoothConnectionController.stopAll()
        super.onDestroy()
    }

    // A system callback automatically triggered the EXACT second the user hits "Allow" or "Deny" in the permission popup window!
    @SuppressLint("MissingPermission")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        // If this is OUR permission request responding...
        if (requestCode == permissionRequestCode) {
            // Check if ALL requests were successfully "GRANTED"
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                updateButtons()
                // Auto scan for phones now that bluetooth works
                if (hasBluetoothPermissions()) {
                    scanBluetoothConnection()
                }
            } else {
                updateButtons() // Disables buttons if denied.
                updateStatus("Bluetooth and microphone permissions are required for live audio streaming")
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    private fun prepareForRecording(){
        // Abort if bluetooth isn't ready or hasn't got permissions.
        if (!ensureBluetoothReady()) {
            return
        }

        // If we don't know who to connect to yet, show the pairing selection popup.
        if (targetDevice == null) {
            scanBluetoothConnection()
            if(targetDevice==null){
                return
            }
        }

        // Immediately launch the background connection thread for the target device!
        bluetoothConnectionController.startClient(targetDevice)
        updateStatus("Connecting to ${targetDevice?.name ?: targetDevice?.address}...")
        updateButtons()
    }

    /**
     * Helper Function: Decides when buttons should be "greyed out" vs "clickable".
     */
    private fun updateButtons(isEnabled: Boolean = hasBluetoothPermissions()) {
        // We wrap things in 'runOnUiThread' because background networking threads are specifically 
        // forbidden from modifying the visual UI directly by Android restrictions.
        val updateAction = {
            // The connect button works if bluetooth is enabled.
            clientButton.isEnabled = isEnabled
            // The recording button ONLY works if we have an ACTIVE connection to the phone.
            recordingButton.isEnabled = isEnabled && bluetoothConnectionController.hasActiveConnection()
            // Dynamically morph the text from Start -> Stop.
            recordingButton.text = if (audioStreamingController.isStreamingAudio) "stop recording" else "start recording"
        }
        runOnUiThread(updateAction)
    }

    /**
     * Checks if all correct Bluetooth permissions represent true.
     */
    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true // They don't need runtime permission below Android 12.
        }

        // Returns true only if EVERY permission string is successfully GRANTED.
        return bluetoothPermissions.all { permission ->
            ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Standard check for the microphone usage permission.
     */
    private fun hasAudioPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Analyzes which specific permissions are missing and throws exactly those to the OS popup array.
     */
    private fun requestMissingPermissions() {
        val missingPermissions = buildList {
            // Loop over Bluetooth ones:
            bluetoothPermissions.forEach { permission ->
                if (ActivityCompat.checkSelfPermission(this@MainActivity, permission) != PackageManager.PERMISSION_GRANTED) {
                    add(permission) // Build our list
                }
            }

            // Include Audio if specifically needed.
            if (!hasAudioPermission()) {
                add(Manifest.permission.RECORD_AUDIO)
            }
        }

        // Tell the OS to display a permission modal to the user right now!
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), permissionRequestCode)
        }
    }

    /**
     * Prevents operations attempting to run when Bluetooth hardware is globally off.
     */
    private fun ensureBluetoothReady(): Boolean {
        if (!hasBluetoothPermissions()) {
            requestMissingPermissions()
            return false
        }

        // Make sure the hardware toggle switch on their watch is active.
        if (!bluetoothAdapter.isEnabled) {
            showToastMessage("Turn Bluetooth on first")
            return false
        }

        return true
    }

    /**
     * Safety checks for all operations needed specifically before audio logic can begin properly running.
     */
    private fun ensureAudioStreamingReady(): Boolean {
        // Bluetooth good?
        if (!ensureBluetoothReady()) {
            return false
        }

        // Connection actually active and initialized?
        if (!bluetoothConnectionController.hasActiveConnection()) {
            showToastMessage("Connect both devices via bluetooth first") // Tells user to connect
            updateButtons()
            return false
        }

        // Do we have legal permissions to capture voice data?
        if (!hasAudioPermission()) {
            requestMissingPermissions()
            return false
        }

        return true
    }

    // --- Helper functions for UI manipulation ---

    private fun updateStatus(message: String) {
        // Execute UI changes cleanly to the main UI thread.
        runOnUiThread {
            textView.text = message
        }
        // Save to system Logcat for developer reference.
        Log.d(tag, message)
    }

    private fun showToastMessage(message: String) {
        // Show pop up immediately
        runOnUiThread {
            toastMessage?.cancel() // Clear the old one first so they don't stack infinitely!
            toastMessage = Toast.makeText(this, message, Toast.LENGTH_SHORT)
            toastMessage?.show()
        }
    }

    // --- Bluetooth Selection Dialog Logics ---

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    private fun scanBluetoothConnection() {
        // Pull already-paired devices natively without doing costly intensive scanning.
        val bondedDevices = bluetoothAdapter.bondedDevices.toList().filter{
            Log.e("TAG", "scanBluetoothConnection: ${it.name}")
            it.bluetoothClass.deviceClass== BluetoothClass.Device.PHONE_SMART
        }


        if (bondedDevices.isEmpty()) {
            targetDevice = null
            updateStatus("No paired phone found. Pair both phones first in Android Bluetooth settings.")
            updateButtons()
            return
        }

        //Auto-select if only one device is paired!
        if (bondedDevices.size == 1) {
            selectTargetDevice(bondedDevices.first())
            return
        }

        // Should only have 1 paired phone! if reach this line, show a picker dialog to handle it.
        showDeviceSelectionDialog(bondedDevices)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun selectTargetDevice(device: BluetoothDevice) {
        targetDevice = device
        
        // buildString allocates less memory than lots of '+' string chaining.
        val summary = buildString {
            append("Selected device:\n")
            append(device.name ?: "Unknown device")
            append("\n")
            append(device.address) // The physical MAC address
        }
        updateStatus(summary)
        updateButtons()
        showToastMessage("Selected ${device.name ?: device.address}")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun showDeviceSelectionDialog(devices: List<BluetoothDevice>) {
        // Create an Array of Strings formatted nicely for a Menu list
        val labels = devices.map { device ->
            "${device.name ?: "Unknown device"}\n${device.address}"
        }.toTypedArray()

        // Pop up the system alert menu
        AlertDialog.Builder(this)
            .setTitle("Choose paired device")
            .setItems(labels) { _, which ->
                // Called when a user taps an item. `which` is the array index.
                selectTargetDevice(devices[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Hardware Callbacks implemented from BluetoothConnectionController interface ---

    // Triggered automatically the second our socket connects natively.
    override fun onConnected(message: String) {
        updateStatus(message)
        updateButtons()
    }

    // Triggered if the phone walks away, turns off Bluetooth, or abruptly ends socket stream.
    override fun onDisconnected(message: String) {
        // Panic-stop recording the mic instantly!
        audioStreamingController.stopStreaming(null)
        updateStatus(message)
        updateButtons()
    }

    // The handler when we receive raw Payload bytes
    override fun onAudioPayloadReceived(payload: ByteArray, size: Int) {
        // Note: The Watch only ever SENDS audio, it doesn't receive audio payload from the phone.
        // Thus, this is an empty no-op.
    }
    
    // The handler when we receive translated Text strings back from the phone
    override fun onTextPayloadReceived(text: String) {
        updateStatus(text)
    }

    // A generic flag that tells the UI to refresh its states.
    override fun onStateChanged() {
        updateButtons()
    }

    // Hook allowing the network stack to trigger native UI popups directly into the view space!
    override fun showToast(message: String) {
        showToastMessage(message)
    }
}