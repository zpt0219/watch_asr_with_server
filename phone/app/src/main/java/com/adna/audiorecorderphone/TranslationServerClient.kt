package com.adna.audiorecorderphone

import android.util.Log // Standard logging for Android Logcat
import java.io.BufferedInputStream // Wraps network streams to prevent slow, chunk-by-chunk reading
import java.io.BufferedOutputStream // Wraps network streams to batch bytes efficiently before sending
import java.io.DataInputStream // Allows reading primitives (Int, Byte) seamlessly from a stream
import java.io.DataOutputStream // Allows writing primitives directly to a stream
import java.io.IOException // Standard network failure exception
import java.net.InetSocketAddress // Represents an IP Address + Port number pairing
import java.net.Socket // Represents a standard internet TCP socket connection
import java.util.concurrent.ExecutorService // A thread pool manager
import java.util.concurrent.Executors // A thread pool factory

/**
 * TCP Client responsible for connecting to a remote translation server (like our Python script)
 * that processes audio STT (Speech-to-Text).
 *
 * It manages the lifecycle of the TCP socket, continuously sending raw audio
 * chunks received via Bluetooth, and subsequently emitting transcript results
 * back to the user interface on a dedicated reader thread.
 */
class TranslationServerClient(
    private val logTag: String,                               // Tag used for logging
    private val updateStatus: (String) -> Unit,               // Callback to overwrite the UI status text
    private val setTranscript: (String) -> Unit,              // Callback to completely overwrite the transcripts text
    private val appendTranscript: (String) -> Unit,           // Callback to add new transcripts to the bottom of the old ones
    private val showToast: (String) -> Unit,                  // Callback to show a popup toast
) {

    // A single threaded "worker" queue. Any task sent to 'executor.execute {}' is run here,
    // guaranteeing that our network calls never block the main UI thread.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    // The physical socket used to talk to the Python server. @Volatile means all threads see changes instantly.
    @Volatile
    private var socket: Socket? = null

    // Pushes bytes up to the server
    @Volatile
    private var output: DataOutputStream? = null

    // Pulls bytes down from the server
    @Volatile
    private var input: DataInputStream? = null

    // A dedicated background thread that loops infinitely waiting for server replies.
    @Volatile
    private var readerThread: Thread? = null

    // Caches the IP and Port so we can silently reconnect if we drop offline.
    @Volatile
    private var currentHost: String? = null
    @Volatile
    private var currentPort: Int? = null

    // Helps us know if we need to manually send a 'Flush' command or not
    @Volatile
    private var hasSentAudioSinceLastFlush: Boolean = false

    /**
     * Queues a request on the background worker thread to connect to the server.
     */
    fun connect(host: String, port: Int) {
        executor.execute {
            ensureConnected(host, port)
        }
    }

    /**
     * Queues a request to funnel raw audio bytes directly to the python TCP server.
     */
    fun sendAudioChunk(host: String, port: Int, payload: ByteArray, size: Int) {
        // Obvious safety check
        if (size <= 0) {
            return
        }

        // Put this transmission in the queue
        executor.execute {
            // First, make sure the socket is actually awake and connected!
            if (!ensureConnected(host, port)) {
                return@execute
            }

            try {
                // 1. Tell Python this is an AUDIO_CHUNK (0x01)
                output?.writeByte(CLIENT_AUDIO_CHUNK)
                // 2. Tell Python exactly how large the chunk is
                output?.writeInt(size)
                // 3. Dump the entire chunk into the stream
                output?.write(payload, 0, size)
                // 4. Force it out the antenna!
                output?.flush()
                
                // Track that we successfully sent some audio
                hasSentAudioSinceLastFlush = true
            } catch (e: IOException) {
                Log.e(logTag, "Failed to send audio to translation server", e)
                showToast("Translation server connection lost")
                closeConnection()
            }
        }
    }

    /**
     * Queues a request to tell the Server: "I'm done speaking. Translate exactly what you have now."
     */
    fun flush() {
        executor.execute {
            // If we didn't send any audio anyway, flushing is a waste of time.
            if (!hasSentAudioSinceLastFlush) {
                return@execute
            }

            try {
                // Tell python this is a FLUSH command (0x02). Size of payload is 0.
                output?.writeByte(CLIENT_FLUSH)
                output?.writeInt(0)
                output?.flush()
                // Reset the tracker because the server's buffer is empty now.
                hasSentAudioSinceLastFlush = false
                updateStatus("Waiting for final transcript from translation server...")
            } catch (e: IOException) {
                Log.e(logTag, "Failed to flush translation server", e)
                closeConnection()
            }
        }
    }

    /**
     * Politely kills the server connection and shuts down all active network threads securely.
     */
    fun release() {
        executor.execute {
            try {
                // If there's unfinished audio dangling, flush it so the final transcript gets processed
                if (hasSentAudioSinceLastFlush) {
                    output?.writeByte(CLIENT_FLUSH)
                    output?.writeInt(0)
                    output?.flush()
                    hasSentAudioSinceLastFlush = false
                }
                
                // Wait politely and then send the CLOSE command (0x03)
                output?.writeByte(CLIENT_CLOSE)
                output?.writeInt(0)
                output?.flush()
            } catch (_: IOException) {
                // Safe to ignore, we are closing it natively down below anyway
            } finally {
                // Force close everything
                closeConnection()
            }
        }
        // Tell the background worker to stop accepting new execute{} commands entirely.
        executor.shutdownNow()
    }

    /**
     * Ensures our TCP socket is genuinely alive. If it isn't, builds an entirely new one securely!
     */
    private fun ensureConnected(host: String, port: Int): Boolean {
        // Fast-path: Check if everything is completely happy already
        val existingSocket = socket
        if (
            existingSocket != null &&
            existingSocket.isConnected &&
            !existingSocket.isClosed &&
            currentHost == host &&
            currentPort == port
        ) {
            return true
        }

        // If it was half-broken or connected to the wrong host, nuke it from orbit.
        closeConnection()

        // Build a fresh socket from scratch!
        return try {
            val newSocket = Socket()
            // Try to connect to the IP/Port. Give up if it stalls for >3 seconds.
            newSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            // 'tcpNoDelay = true' disables Nagle's algorithm. Audio has low latency requirements,
            // we don't want the OS trying to "batch" packets to save bandwidth. Send them immediately!
            newSocket.tcpNoDelay = true

            socket = newSocket
            
            // Wrap the data streams so we can efficiently writeBytes and readInts
            output = DataOutputStream(BufferedOutputStream(newSocket.getOutputStream()))
            input = DataInputStream(BufferedInputStream(newSocket.getInputStream()))
            
            // Cache state
            currentHost = host
            currentPort = port
            hasSentAudioSinceLastFlush = false
            
            // Start the infinite "listening" thread
            startReaderThread(host, port)
            
            updateStatus("Connected to translation server $host:$port")
            true
        } catch (e: IOException) {
            // Failed. Often happens if IP is a typo, port is wrong, or PC firewall blocks the connection.
            Log.e(logTag, "Unable to connect to translation server", e)
            updateStatus("Unable to connect to translation server $host:$port")
            showToast("Cannot reach translation server")
            
            closeConnection() // Wipe the broken socket completely
            false
        }
    }

    /**
     * This thread sits in the background forever and furiously listens to anything the Python script says back!
     */
    private fun startReaderThread(host: String, port: Int) {
        readerThread = Thread {
            try {
                // Infinite loop!
                while (true) {
                    // Try to read exactly 1 byte. (The '?: break' means if the stream crashes, exit the loop instantly).
                    val messageType = input?.readUnsignedByte() ?: break
                    // Try to read the 4 byte integer specifying how long the text is
                    val payloadSize = input?.readInt() ?: break
                    
                    // Prevent memory-exhaustion hacks from malicious servers
                    if (payloadSize < 0 || payloadSize > MAX_SERVER_PAYLOAD_BYTES) {
                        throw IOException("Invalid payload size from server: $payloadSize")
                    }

                    // Pre-allocate a nice clean empty Array of bytes precisely sized for the text string.
                    val payload = ByteArray(payloadSize)
                    if (payloadSize > 0) {
                        // Suck the data explicitly!
                        input?.readFully(payload)
                    }
                    // Handle that string!
                    handleServerMessage(messageType, payload)
                }
            } catch (e: IOException) {
                // Very common exception. Happens normally when Python script is killed (Ctrl+C).
                Log.w(logTag, "Translation server reader stopped", e)
                updateStatus("Translation server disconnected")
            } finally {
                // Die gracefully
                closeConnection()
            }
        }.apply {
            // Name the thread for debugger profiling.
            name = "TranslationServerReader"
            // Making it a "Daemon" thread means if the Android App crashes or closes, the OS will instantly kill this thread too!
            isDaemon = true
            start() // Go!
        }

        setTranscript("Waiting for translated text from $host:$port ...")
    }

    /**
     * Converts raw bytes into text depending on what the python server classified it as!
     */
    private fun handleServerMessage(messageType: Int, payload: ByteArray) {
        // Convert strict Network ByteArray into a human-readable UTF-8 String!
        val text = payload.toString(Charsets.UTF_8).trim()
        
        when (messageType) {
            // 0x11 represents a successful transcription packet
            SERVER_TRANSCRIPT -> {
                if (text.isNotEmpty()) {
                    // Throw it onto the screen at the bottom of the list
                    appendTranscript(text)
                    updateStatus("Received transcript from translation server")
                }
            }

            // 0x12 represents Whisper crashing or malfunctioning
            SERVER_ERROR -> {
                if (text.isNotEmpty()) {
                    appendTranscript("[server error] $text")
                    showToast("Translation server error")
                }
            }

            // The python script sent something we didn't program for.
            else -> Log.w(logTag, "Unknown server message type: $messageType")
        }
    }

    /**
     * Gracefully wipes the sockets out of memory to prevent Android resource leaking.
     */
    private fun closeConnection() {
        // Note: The try-catch block ignores errors. If the socket fails to close, it's already dead!
        try {
            input?.close()
        } catch (_: IOException) {
        }
        try {
            output?.close()
        } catch (_: IOException) {
        }
        try {
            socket?.close()
        } catch (_: IOException) {
        }

        // Nullify everything
        input = null
        output = null
        socket = null
        currentHost = null
        currentPort = null
        hasSentAudioSinceLastFlush = false
    }

    // A companion object is Kotlin's way of doing Java "static" globals.
    private companion object {
        // Tags pushed TO the python script
        const val CLIENT_AUDIO_CHUNK = 0x01
        const val CLIENT_FLUSH = 0x02
        const val CLIENT_CLOSE = 0x03

        // Tags received FROM the python script
        const val SERVER_TRANSCRIPT = 0x11
        const val SERVER_ERROR = 0x12

        // Wait a maximum of 3000ms (3 seconds) trying to find the server before giving up
        const val CONNECT_TIMEOUT_MS = 3_000
        // Max payload size to accept natively: 256 Kilobytes of text.
        const val MAX_SERVER_PAYLOAD_BYTES = 256 * 1024
    }
}
