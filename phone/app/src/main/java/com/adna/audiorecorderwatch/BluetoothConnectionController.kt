package com.adna.audiorecorderphone

import android.Manifest // Standard Android permissions constants
import android.annotation.SuppressLint // Used to suppress compiler permission warnings defensively
import android.bluetooth.BluetoothAdapter // Represents the actual physical Bluetooth chip
import android.bluetooth.BluetoothDevice // Represents the remote device (the watch)
import android.bluetooth.BluetoothServerSocket // A socket used strictly to 'listen' for incoming connections
import android.bluetooth.BluetoothSocket // An active, established two-way connection pipe
import android.util.Log // Standard logging class
import androidx.annotation.RequiresPermission // Hints to the compiler that calling this needs specific permissions
import java.io.DataInputStream // Allows reading specific data types (like Ints) from raw byte streams
import java.io.DataOutputStream // Allows writing specific data types to raw byte streams
import java.io.IOException // Exception thrown when networking hardware fails
import java.util.UUID // Universally Unique Identifier

/**
 * Manages the Bluetooth RFCOMM connection between the phone and watch.
 *
 * This phone version of the controller almost identically matches the watch version.
 * It primarily listens as a 'server', waiting for the watch to connect and send audio.
 */
class BluetoothConnectionController(
    // A reference to the physical Bluetooth adapter on the phone
    private val bluetoothAdapter: BluetoothAdapter,
    // The tag used when printing to Logcat
    private val logTag: String,
    // The exact secure UUID that specifies "Our App" vs any other Bluetooth app.
    private val appUuid: UUID,
    // The human-readable name we broadcast when acting as a Server
    private val serviceName: String,
    // Callbacks to send UI updates back to MainActivity
    private val callbacks: Callbacks,
) {

    // Interface defining how we communicate results back to the View
    interface Callbacks {
        fun onConnected(message: String) // Fired when connection succeeds
        fun onDisconnected(message: String) // Fired when connection drops
        fun onAudioPayloadReceived(payload: ByteArray, size: Int) // Fired EVERY time audio bytes arrive
        fun onStateChanged() // Fired to prompt UI buttons to refresh 
        fun showToast(message: String) // Fired to display a tiny pop-up message
    }

    // Three entirely separate background threads we use to manage networking seamlessly
    private var serverThread: ServerThread? = null
    private var clientThread: ClientThread? = null
    private var connectedThread: ConnectedThread? = null

    // @Volatile means all threads always read the absolute newest value of this 
    // from main memory, preventing a thread from using cached, outdated sockets.
    @Volatile
    private var activeSocket: BluetoothSocket? = null

    // Safety check function to see if we actually have an active, physical connection 
    fun hasActiveConnection(): Boolean = connectedThread != null && activeSocket?.isConnected == true

    /**
     * Starts the device listening silently in the background for an incoming connection.
     * On the Phone app, this is typically the only part used!
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startServer() {
        if (serverThread != null) {
            // Already listening. Prevent starting multiple servers!
            callbacks.showToast("Server is already waiting for a connection")
            return
        }

        // Initialize the Server thread and instantly start() it.
        serverThread = ServerThread().also { it.start() }
        callbacks.onStateChanged()
    }

    /**
     * Aggressively attempts to connect outwardly to a specific device.
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    fun startClient(device: BluetoothDevice?) {
        if (device == null) {
            callbacks.showToast("No target device selected")
            return
        }

        if (clientThread != null) {
            // Prevent attempting to connect twice simultaneously
            callbacks.showToast("Client connection is already in progress")
            return
        }

        // Initialize the Client thread and instantly start() it.
        clientThread = ClientThread(device).also { it.start() }
        callbacks.onStateChanged()
    }

    /**
     * A "panic button" method that strictly kills all threads related to this connection.
     */
    fun stopAll() {
        connectedThread?.cancel()
        clientThread?.cancel()
        serverThread?.cancel()
    }

    /**
     * Pushes bytes onto the connection queue to be sent to the remote device.
     */
    fun sendAudioChunk(audioBytes: ByteArray, size: Int): Boolean {
        return connectedThread?.writeAudioChunk(audioBytes, size) == true
    }

    /**
     * Triggered safely when either the Server 'accepts' a connection, or the Client 'connects' to one.
     * It promotes the bare Socket into a full 'ConnectedThread'.
     */
    @SuppressLint("MissingPermission")
    private fun handleConnectedSocket(socket: BluetoothSocket, message: String) {
        // Destroy existing stream if we somehow had one.
        connectedThread?.cancel()
        
        // Save the new connection globally 
        activeSocket = socket
        
        // Start the thread that actually listens for incoming Audio bytes infinitely
        connectedThread = ConnectedThread(socket).also { it.start() }
        
        // We're connected now, we don't need to listen or aggressively seek anymore!
        clientThread = null
        serverThread = null
        
        // Push notifications to UI
        callbacks.onConnected(message)
        callbacks.onStateChanged()
        callbacks.showToast(message)
    }

    /**
     * Fully destroys local networking states and informs the app that the connection died.
     */
    private fun clearConnectionState(socket: BluetoothSocket? = null, message: String? = null) {
        // We only clear if the dying socket is actually our "Active" socket (or if we force clear via null)
        if (socket == null || activeSocket == socket) {
            activeSocket = null
            connectedThread = null
            clientThread = null
            serverThread = null
            
            // Only trigger a disconnect message if one was provided
            message?.let(callbacks::onDisconnected)
            callbacks.onStateChanged()
        }
    }

    // ------------------------------------------------------------------------------------------
    // THE THREADS
    // ------------------------------------------------------------------------------------------

    /**
     * The background thread that aggressively attempts to dial out.
     */
    inner class ClientThread(private val device: BluetoothDevice) : Thread() {
        // Prepare the socket based on our universal app UUID.
        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(appUuid)
        }

        @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
        override fun run() {
            // Cancel discovery before connecting because Bluetooth scanning makes connection attempts fail.
            bluetoothAdapter.cancelDiscovery()
            Log.d(logTag, "Client thread started")

            mmSocket?.let { socket ->
                try {
                    // This freezes the thread until connection succeeds or fails.
                    socket.connect()
                    
                    // Connected successfully! Pass it up to the controller.
                    handleConnectedSocket(socket, "Connected to ${device.name ?: device.address}")
                } catch (e: IOException) {
                    // Fails safely without crashing app
                    Log.e(logTag, "Client connect failed", e)
                    callbacks.showToast("Connection failed. Make sure the other phone started the server.")
                    
                    clearConnectionState(socket)
                    try {
                        socket.close() // Close the broken socket
                    } catch (closeException: IOException) {
                        Log.e(logTag, "Unable to close failed client socket", closeException)
                    }
                } finally {
                    clientThread = null // Destroy self reference
                    callbacks.onStateChanged()
                }
            }
        }

        // Safety method to manually kill this thread if the user gets bored waiting 
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
     * The background thread that passively sits and listens for any incoming connections.
     */
    @SuppressLint("MissingPermission")
    inner class ServerThread : Thread() {
        // Prepare a listening port (similar to binding a normal TCP server)
        private val mmServerSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            bluetoothAdapter.listenUsingRfcommWithServiceRecord(serviceName, appUuid)
        }

        override fun run() {
            var shouldLoop = true
            Log.d(logTag, "Server thread started")

            while (shouldLoop) {
                val socket: BluetoothSocket? = try {
                    // .accept() completely freezes this thread indefinitely until a client connects to us!
                    mmServerSocket?.accept()
                } catch (e: IOException) {
                    // Usually occurs because the user turned bluetooth off, or we told it to close() manually.
                    Log.e(logTag, "Server accept failed", e)
                    shouldLoop = false
                    null
                }

                // If socket is not null, it means we got a connection!
                socket?.also {
                    // Give the connected socket to our central handler. 
                    handleConnectedSocket(
                        it,
                        "Connected with ${it.remoteDevice?.name ?: it.remoteDevice?.address}"
                    )
                    
                    try {
                        // After one device connects, stop the server listening port immediately.
                        // We only want a strict 1-to-1 connection.
                        mmServerSocket?.close()
                    } catch (e: IOException) {
                        Log.e(logTag, "Unable to close server socket after accept", e)
                    }
                    // Break the infinite loop!
                    shouldLoop = false
                }
            }

            // Cleanup 
            serverThread = null
            callbacks.onStateChanged()
        }

        fun cancel() {
            try {
                // By closing the socket while it's stuck in "accept()", it instantly wakes it up and triggers the IOException!
                mmServerSocket?.close()
            } catch (e: IOException) {
                Log.e(logTag, "Unable to close server socket", e)
            } finally {
                serverThread = null
                callbacks.onStateChanged()
            }
        }
    }

    /**
     * The actively talking/listening thread responsible for pulling bytes from the stream 
     * and shipping them up to MainActivity.
     */
    inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
        // Data streams are easier to use than Raw Byte Streams.
        private val mmInStream = DataInputStream(mmSocket.inputStream)
        private val mmOutStream = DataOutputStream(mmSocket.outputStream)

        // The background loop constantly waiting to read files.
        override fun run() {
            while (true) {
                try {
                    // 1. Read message type (1 byte)
                    val messageType = mmInStream.readUnsignedByte()
                    // 2. Read payload length (4 bytes)
                    val payloadSize = mmInStream.readInt()

                    // Safety check against malicious/corrupted data lengths
                    if (payloadSize <= 0 || payloadSize > BluetoothMessage.MAX_PAYLOAD_BYTES) {
                        throw IOException("Invalid payload size: $payloadSize")
                    }

                    // 3. Prepare chunk and fill it completely.
                    val payload = ByteArray(payloadSize)
                    mmInStream.readFully(payload)

                    // Forward it to the UI!
                    when (messageType) {
                        BluetoothMessage.AUDIO -> callbacks.onAudioPayloadReceived(payload, payloadSize)
                        else -> Log.w(logTag, "Unsupported message type: $messageType")
                    }
                } catch (e: IOException) {
                    // Error here implies the watch was turned off or walked out of range
                    Log.e(logTag, "Input stream disconnected", e)
                    callbacks.showToast("Bluetooth connection ended")
                    clearConnectionState(mmSocket, "Bluetooth connection ended")
                    break
                }
            }
        }

        /**
         * The function that pushes outgoing memory chunks to the network stack!
         * This isn't really used by the Phone app because we only RECEIVE audio from the watch, 
         * but it's here so the API mirrors the Watch app perfectly.
         */
        @Synchronized
        fun writeAudioChunk(audioBytes: ByteArray, size: Int): Boolean {
            if (!mmSocket.isConnected) {
                callbacks.showToast("Bluetooth connection was lost. Reconnect both phones.")
                cancel()
                return false
            }

            try {
                mmOutStream.writeByte(BluetoothMessage.AUDIO)
                mmOutStream.writeInt(size)
                mmOutStream.write(audioBytes, 0, size)
                mmOutStream.flush()
                return true
            } catch (e: IOException) {
                Log.e(logTag, "Error occurred while sending audio", e)
                callbacks.showToast("Connection closed. Start server again and reconnect.")
                cancel()
                return false
            }
        }

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
