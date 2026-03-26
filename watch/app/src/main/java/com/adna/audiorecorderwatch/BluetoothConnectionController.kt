package com.adna.audiorecorderwatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Manages the Bluetooth RFCOMM connection between the phone and watch.
 *
 * This controller can act as either the server (listening for incoming connections)
 * or the client (initiating a connection to a paired device). It handles thread
 * lifecycle, socket management, and bidirectional data transmission.
 */
class BluetoothConnectionController(
    // The physical Bluetooth chip adapter of the device.
    private val bluetoothAdapter: BluetoothAdapter,
    // Tag used for logging to Logcat.
    private val logTag: String,
    // A universal unique identifier that the Server and Client must share to connect correctly.
    private val appUuid: UUID,
    // A friendly name for the service broadcasted by the server.
    private val serviceName: String,
    // UI callbacks passed from MainActivity to update the screen.
    private val callbacks: Callbacks,
) {

    // Defines the contract for communicating with the UI (MainActivity).
    interface Callbacks {
        fun onConnected(message: String) // Called when a phone connects
        fun onDisconnected(message: String) // Called when the socket dies
        fun onAudioPayloadReceived(payload: ByteArray, size: Int) // Called when we receive audio bytes
        fun onTextPayloadReceived(text: String) // Called when a string of translated text is sent back
        fun onStateChanged() // Called to tell the UI to refresh its buttons.
        fun showToast(message: String) // Called to show a tiny popup window
    }

    // Thread responsible for aggressively "connecting" to the other phone
    private var clientThread: ClientThread? = null
    // Thread responsible for the actual talking, listening, and streaming back-and-forth data
    private var connectedThread: ConnectedThread? = null

    // The single active connection object currently alive
    @Volatile // Makes sure thread reads the latest value of this variable directly from RAM unconditionally
    private var bluetoothSocket: BluetoothSocket? = null

    // Checks whether we currently have a background thread actively sending/receiving bytes.
    fun hasActiveConnection(): Boolean =  connectedThread != null && bluetoothSocket?.isConnected == true

    /**
     * Boot up the Client. It chooses exactly who to connect to, and forcefully demands a connection.
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    fun startClient(device: BluetoothDevice?) {
        // If the user hasn't selected a device, cancel.
        if (device == null) {
            callbacks.showToast("No target device selected")
            return
        }

        // If we are already running a client thread, cancel the second click.
        if (clientThread != null) {
            callbacks.showToast("Client connection is already in progress")
            return
        }

        // Create the aggressive connection thread, save it, and start it immediately.
        clientThread = ClientThread(device).also { it.start() }
        callbacks.onStateChanged()
    }

    /**
     * Kills all threads entirely, stopping all forms of Bluetooth connections.
     */
    fun stopAll() {
        connectedThread?.cancel() // Stop the active stream.
        clientThread?.cancel()    // Stop the connection attempt.
    }

    /**
     * Sends the chunk of audio to whoever we are connected with!
     */
    fun sendAudioChunk(audioBytes: ByteArray, size: Int): Boolean {
        // The '?.writeAudioChunk' safely asks the running stream to deliver the bytes.
        // It returns whether it succeeded. We compare it to "== true".
        return connectedThread?.writeAudioChunk(audioBytes, size) == true
    }

    /**
     * Internal magic: Once the Server or the Client succeeds in shaking hands,
     * they both call this function to promote the Socket into a full "ConnectedStream".
     */
    @SuppressLint("MissingPermission")
    private fun handleConnectedSocket(socket: BluetoothSocket, message: String) {
        // Cancel the old stream if there happened to be one.
        connectedThread?.cancel()
        
        // This is now the "real" active socket.
        bluetoothSocket = socket
        
        // Boot up the two-way talking/listening thread!
        connectedThread = ConnectedThread(socket).also { it.start() }
        
        // Because the socket is already established, we don't need the server or client anymore.
        clientThread = null
        
        // Notify the MainActivity that we successfully connected.
        callbacks.onConnected(message)
        callbacks.onStateChanged()
        callbacks.showToast(message)
    }

    /**
     * Safely deletes every trace of a current connection and resets everything.
     */
    private fun clearConnectionState(socket: BluetoothSocket? = null, message: String? = null) {
        // Only clear if the socket attempting to clear matches the currently active socket.
        if (socket == null || bluetoothSocket == socket) {
            bluetoothSocket = null
            connectedThread = null
            clientThread = null
            
            // Send the disconnect message to the UI
            message?.let(callbacks::onDisconnected)
            callbacks.onStateChanged()
        }
    }

    /**
     * The aggressive background thread that forcefully attempts to connect to a specific Device.
     */
    inner class ClientThread(private val device: BluetoothDevice) : Thread() {
        // lazily initialize the standard Bluetooth socket based on our UUID UUID
        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(appUuid)
        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
        override fun run() {
            // Cancel discovery before connecting. It eats massive system resources to 
            // search for physical devices while attempting to create a stable connection.
            bluetoothAdapter.cancelDiscovery()
            Log.d(logTag, "Client thread started")

            mmSocket?.let { socket ->
                try {
                    // Start attempting to connect. This '.connect()' completely FREEZES this thread
                    // until it works, throws an error, or times out.
                    socket.connect()
                    
                    // If we made it past .connect() without breaking, it succeeded! Give it to the controller.
                    handleConnectedSocket(socket, "Connected to ${device.name ?: device.address}")
                    
                } catch (e: IOException) {
                    // Network connection failed completely.
                    Log.e(logTag, "Client connect failed", e)
                    callbacks.showToast("Connection failed. Make sure the other phone started the server.")
                    
                    // Wipe the state
                    clearConnectionState(socket)
                    try {
                        socket.close() // Close off whatever broken connection tried to exist
                    } catch (closeException: IOException) {
                        Log.e(logTag, "Unable to close failed client socket", closeException)
                    }
                } finally {
                    // Wipe the threads and update UI
                    clientThread = null
                    callbacks.onStateChanged()
                }
            }
        }

        // Kill this aggressive attempt manually
        fun cancel() {
            try {
                mmSocket?.close()
            } catch (e: IOException) {
                Log.e(logTag, "Unable to close client socket", e)
            } finally {
                clientThread = null
                callbacks.onStateChanged()
            }
        }
    }

    /**
     * The active, living thread that pushes Data back and forth physically.
     */
    inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
        // Data streams let us do advanced actions like "readInt" or "writeByte" instead of parsing raw Bytes.
        private val mmInStream = DataInputStream(mmSocket.inputStream)
        private val mmOutStream = DataOutputStream(mmSocket.outputStream)

        // The background logic that constantly listens for new inbound payload from the partner.
        override fun run() {
            // Loop infinitely until the connection disconnects.
            while (true) {
                try {
                    // 1. Read the 1 Byte message type. (Freeze thread if we have nothing yet). 
                    val messageType = mmInStream.readUnsignedByte()
                    // 2. Read the integer (4 bytes) dictating how large the upcoming file is.
                    val payloadSize = mmInStream.readInt()

                    // Sanity check: If they tried to send us 5GB of payload, immediately crash and throw.
                    if (payloadSize <= 0 || payloadSize > Constants.MAX_PAYLOAD_BYTES) {
                        throw IOException("Invalid payload size: $payloadSize")
                    }

                    // 3. Make an empty array exactly the size of the packet size.
                    val payload = ByteArray(payloadSize)
                    // 4. Forcefully read the stream until we fetch exactly the calculated size!
                    mmInStream.readFully(payload)

                    // Decide what to do based on the message type
                    when (messageType) {
                        // In this app, watch doesn't receive audio, but we map it just in case! 
                        Constants.AUDIO -> callbacks.onAudioPayloadReceived(payload, payloadSize)
                        // A brand new text packet came in! Decode and fire exactly as expected.
                        Constants.TEXT -> {
                            val textString = String(payload, Charsets.UTF_8)
                            callbacks.onTextPayloadReceived(textString)
                        }
                        else -> Log.w(logTag, "Unsupported message type: $messageType")
                    }
                } catch (e: IOException) {
                    // Connection was physically lost.
                    Log.e(logTag, "Input stream disconnected", e)
                    callbacks.showToast("Bluetooth connection ended")
                    // Notify UI and destroy state
                    clearConnectionState(mmSocket, "Bluetooth connection ended")
                    break // End this thread's loop forever. 
                }
            }
        }

        /**
         * The function that pushes outgoing memory chunks to the network stack!
         * Synchronized means that if two background tasks try to write an audio piece at the exact
         * same millisecond, they will form a line and queue up gracefully instead of crashing into memory together.
         */
        @Synchronized
        fun writeAudioChunk(audioBytes: ByteArray, size: Int): Boolean {
            // Hard panic check if connection actually exists.
            if (!mmSocket.isConnected) {
                callbacks.showToast("Bluetooth connection was lost. Reconnect both phones.")
                cancel()
                return false
            }

            try {
                // 1. We write the MessageType byte (0x01 = AUDIO)
                mmOutStream.writeByte(Constants.AUDIO)
                // 2. We write the 4-byte Int (How large the piece of audio is!)
                mmOutStream.writeInt(size)
                // 3. We write the entire raw piece of Audio Data
                mmOutStream.write(audioBytes, 0, size)
                
                // Flush forces those bytes into the physical antennas instead of staying in memory cache
                mmOutStream.flush()
                return true
            } catch (e: IOException) {
                // It failed to write! Oh no. Connection lost...
                Log.e(logTag, "Error occurred while sending audio", e)
                callbacks.showToast("Connection closed. Start server again and reconnect.")
                cancel()
                return false
            }
        }

        // Close the active socket
        fun cancel() {
            try {
                mmSocket.close()
            } catch (e: IOException) {
                Log.e(logTag, "Unable to close connected socket", e)
            } finally {
                clearConnectionState(mmSocket)
            }
        }
    }
}
