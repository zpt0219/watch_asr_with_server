package com.adna.audiorecorderwatch

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission

/**
 * Controller responsible for capturing raw audio from the device's microphone
 * and streaming it in chunks to a connected Bluetooth device.
 *
 * This class is utilized specifically by the Watch module to act as the audio sender.
 */
class AudioStreamingController(
    private val logTag: String,                                       // Tag used for logging to Android Logcat
    private val updateStatus: (String) -> Unit,                       // Function passed in to update the UI text
    private val showToast: (String) -> Unit,                          // Function passed in to show a pop-up toast
    private val hasActiveConnection: () -> Boolean,                   // Function passed in to check if Bluetooth is connected
    private val sendAudioChunk: (ByteArray, Int) -> Boolean,          // Function passed in to actually send bytes over Bluetooth
    private val onStreamingStateChanged: () -> Unit,                  // Function passed in to refresh the UI buttons
) {

    // Audio configuration specifically chosen because Whisper AI models expect 16kHz audio.
    // 16000 samples per second.
    private val sampleRateHz = 16000
    // Every single audio sample is 16 bits (2 bytes) large.
    private val pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
    // We only need one audio channel (Mono), since we are just recording a voice.
    private val inputChannelConfig = AudioFormat.CHANNEL_IN_MONO

    // The native Android object that talks to the physical microphone hardware.
    private var audioRecord: AudioRecord? = null
    // A background thread dedicated specifically to reading the microphone as fast as possible.
    private var audioRecordThread: Thread? = null

    // @Volatile means changes to this variable are immediately visible to all other threads.
    @Volatile
    var isStreamingAudio: Boolean = false
        private set // The 'set' is private, meaning other classes can read it but only this class can change it.

    /**
     * Initializes the microphone hardware and starts a background thread to continuously
     * read raw PCM audio chunks and send them over the active Bluetooth connection.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startStreaming() {
        // If we are already streaming, doing it again would cause a crash, so we just return.
        if (isStreamingAudio) {
            return
        }

        // We ask Android: "What's the absolute minimum memory buffer size needed for this audio format?"
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRateHz, inputChannelConfig, pcmEncoding)
        // If it returns 0 or less, the phone/watch physically doesn't support this audio format.
        if (minBufferSize <= 0) {
            showToast("Unable to initialize microphone recording")
            return
        }

        // We choose a buffer size that is at least exactly the minimum, but ideally at least 4096 bytes.
        val bufferSize = minBufferSize.coerceAtLeast(4096)
        
        // We create the AudioRecord object, requesting the main Microphone source.
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRateHz,
            inputChannelConfig,
            pcmEncoding,
            bufferSize
        )

        // If another app is using the mic, it might fail to initialize. We check state here.
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release() // Free the resources back to the OS.
            showToast("Microphone is not available")
            return
        }

        // Successfully initialized. Store the recorder.
        audioRecord = recorder
        // Update our state flag.
        isStreamingAudio = true
        // Tell the MainActivity to update the buttons to show "stop recording".
        onStreamingStateChanged()
        // Update the screen text.
        updateStatus("Streaming microphone audio to the connected phone...")
        showToast("Audio streaming started")

        // Start a completely new background thread so the UI doesn't freeze while we loop continuously.
        audioRecordThread = Thread {
            try {
                // Actually start pulling electrons off the microphone hardware.
                recorder.startRecording()
                // Create a temporary array in memory to hold the bytes we read.
                val buffer = ByteArray(bufferSize)

                // Loop infinitely as long as the user hasn't pressed the "stop" button.
                while (isStreamingAudio) {
                    // Try to read exactly 'buffer.size' bytes from the microphone. 
                    // This command pauses the thread until it has grabbed some audio.
                    val bytesRead = recorder.read(buffer, 0, buffer.size)
                    
                    // If we successfully read 1 or more bytes...
                    if (bytesRead > 0) {
                        // Forward the raw chunk to the Bluetooth connection using our callback!
                        val writeSucceeded = sendAudioChunk(buffer, bytesRead)
                        // If sending failed (e.g. bluetooth disconnected suddenly), break the recording loop.
                        if (!writeSucceeded) {
                            break
                        }
                    } else if (bytesRead < 0) {
                        // If bytesRead is negative, it's actually an Android error code.
                        Log.e(logTag, "AudioRecord read failed: $bytesRead")
                        break
                    }
                }
            } catch (e: Exception) {
                // Catch any unexpected crashes (like permissions revoked mid-stream).
                Log.e(logTag, "Audio streaming failed", e)
                showToast("Audio streaming stopped unexpectedly")
            } finally {
                // The 'finally' block always executes when the thread ends, ensuring we clean up the hardware.
                stopStreaming(null)
            }
        }.apply {
            // Give the thread a name so it's easy to spot when profiling or debugging.
            name = "AudioRecordThread"
            start() // Kick off the thread!
        }
    }

    /**
     * Halts the microphone recording and joins the background thread.
     */
    fun stopStreaming(statusMessage: String?) {
        // Change the flag so the while loop in the background thread terminates naturally.
        isStreamingAudio = false

        // Grab a local reference to the recorder and clear the class property to avoid double-stops.
        val recorder = audioRecord
        audioRecord = null
        
        try {
            // Check if it's currently in the middle of a recording block.
            if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                // Ask the hardware to stop capturing audio.
                recorder.stop()
            }
        } catch (e: IllegalStateException) {
            // This happens if the user presses stop while it's already stopping or broken.
            Log.w(logTag, "AudioRecord stop called in invalid state", e)
        } finally {
            // Release the physical hardware back to the OS so other apps can use the mic.
            recorder?.release()
        }

        // Wait for the background thread to finish winding down.
        val recordThread = audioRecordThread
        // Check if the thread exists and ensure we aren't accidentally trying to join ourselves (deadlock).
        if (recordThread != null && recordThread !== Thread.currentThread()) {
            // Pause the main thread for max 300 milliseconds waiting for the recording thread to finish shutting down.
            recordThread.join(300)
        }
        audioRecordThread = null

        // Tell the UI the state has finally completely stopped.
        onStreamingStateChanged()
        
        // Update the textual status if requested, but only if Bluetooth hasn't totally died anyway.
        if (statusMessage != null && hasActiveConnection()) {
            updateStatus(statusMessage)
        }
    }

    /**
     * Safely releases any active recording streams and hardware references.
     * This is usually called when the user hard-closes the app completely.
     */
    fun releaseAll() {
        stopStreaming(null)
    }
}
