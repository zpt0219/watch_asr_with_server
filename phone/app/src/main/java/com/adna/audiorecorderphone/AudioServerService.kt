package com.adna.audiorecorderphone

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.UUID

/**
 * A Foreground Service that runs continuously in the background to handle the Bluetooth
 * connection with the smartwatch, and the TCP connection with the Translation Server.
 * 
 * Running as a Foreground Service ensures Android doesn't kill the connection when the user 
 * switches to another app or locks their phone screen.
 */
class AudioServerService : Service() {

    // Used for identifying log messages from this class in Logcat
    private val tag = "AudioServerService"
    
    // Identifier for the background notification channel required by Android 8.0+
    private val channelId = "AudioServerServiceChannel"
    
    // An arbitrary integer ID used to identify our persistent foreground notification
    private val notificationId = 1
    
    // Core controllers
    // Handles the actual low-level Bluetooth RFCOMM socket to the watch
    private var connectionController: BluetoothConnectionController? = null
    // Handles the TCP socket connection to the Python script doing Speech-to-Text
    private var translationServerClient: TranslationServerClient? = null
    
    // The exact secure UUID that specifies "Our App" vs any other Bluetooth app.
    // Must match the UUID used in the watch app!
    private val appUuid = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    
    // The human-readable name we broadcast when acting as a Server
    private val serviceName = "audiorecorderphone"
    
    // Binder for UI - allows the MainActivity to grab a direct reference to this Service
    private val binder = LocalBinder(this)
    
    // Callbacks to UI - Used to pass events from the background service back to the screen
    interface UiCallbacks {
        fun onStatusUpdate(message: String) // Update the top status text
        fun onTranscriptSet(message: String) // Overwrite the transcript box
        fun onTranscriptAppended(message: String) // Add to the transcript box
        fun onToast(message: String) // Show a quick pop-up message
        fun onStateChanged() // Tell the UI to update its buttons (enable/disable)
    }
    
    // Holds the reference to the MainActivity (if it is currently open)
    private var uiCallbacks: UiCallbacks? = null
    
    // Cache the IP and Port so we can automatically connect when the watch connects
    private var translationHost: String? = null
    private var translationPort: Int = 0

    /**
     * A standard Binder implementation that just returns this service instance.
     * This is how MainActivity "talks" to the background service.
     */
    class LocalBinder(private val service: AudioServerService) : Binder() {
        fun getService(): AudioServerService = service
    }

    /**
     * Called by Android exactly once when the service is first created.
     */
    override fun onCreate() {
        super.onCreate()
        
        // Android 8.0+ requires notification channels to be created before showing notifications
        createNotificationChannel()
        
        // Get a reference to the phone's physical Bluetooth hardware
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        
        // Initialize the controller that talks to the watch
        connectionController = BluetoothConnectionController(
            bluetoothAdapter = bluetoothAdapter,
            logTag = tag,
            appUuid = appUuid,
            serviceName = serviceName,
            callbacks = bluetoothCallbacks 
        )
        
        // Initialize the client that talks to the Python translation server
        translationServerClient = TranslationServerClient(
            logTag = tag,
            updateStatus = { msg -> uiCallbacks?.onStatusUpdate(msg) },
            setTranscript = { msg -> 
                uiCallbacks?.onTranscriptSet(msg)
                // Also forward this text back to the watch over Bluetooth!
                connectionController?.sendTextChunk(msg)
            },
            appendTranscript = { msg -> 
                uiCallbacks?.onTranscriptAppended(msg)
                // Also forward this text back to the watch over Bluetooth!
                connectionController?.sendTextChunk(msg)
            },
            showToast = { msg -> uiCallbacks?.onToast(msg) }
        )
    }

    /**
     * Called every time MainActivity calls startService() or startForegroundService().
     * This is where we receive the intent containing the Server IP and Port.
     */
    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Extract the IP Address and Port from the intent that started us
        val host = intent?.getStringExtra("EXTRA_HOST")
        val port = intent?.getIntExtra("EXTRA_PORT", 0) ?: 0
        
        if (host != null && port > 0) {
            translationHost = host
            translationPort = port
        }
        
        // Immediately promote this service to a Foreground Service so Android doesn't kill it
        startForegroundServiceNotification()
        
        // Start bluetooth listening (waiting for the watch to connect)
        connectionController?.startServer()
        
        // Inform the UI
        uiCallbacks?.onStatusUpdate("Server started in background. Ongoing via notification.")
        uiCallbacks?.onStateChanged()
        
        // START_NOT_STICKY means if Android's memory gets so full it kills us,
        // it shouldn't try to automatically restart us with a null intent later.
        return START_NOT_STICKY
    }
    
    /**
     * Builds and displays the un-swipeable foreground notification.
     */
    private fun startForegroundServiceNotification() {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Audio Relay Active")
            .setContentText("Listening for watch connection...")
            .setSmallIcon(R.mipmap.ic_launcher) // The little icon in the status bar
            .setOngoing(true) // Makes it so the user can't swipe it away
            .build()
            
        // Android 10+ (API 29) requires us to declare exactly what "type" of foreground service this is
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(notificationId, notification)
        }
    }

    /**
     * Registers the notification channel with the Android system (required for Android 8.0+).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelId,
                "Background Audio Relay",
                NotificationManager.IMPORTANCE_LOW // Low importance means no sound/vibration
            )
            val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }

    /**
     * Called when MainActivity calls bindService().
     * We return our custom Binder so MainActivity can call functions on us directly.
     */
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    /**
     * Called when the service is finally being destroyed (e.g., app is force closed).
     */
    override fun onDestroy() {
        // Shut down all networking threads to prevent memory leaks
        connectionController?.stopAll()
        translationServerClient?.release()
        super.onDestroy()
    }
    
    /**
     * Allows MainActivity to register itself to receive UI update callbacks.
     */
    fun setUiCallbacks(callbacks: UiCallbacks?) {
        this.uiCallbacks = callbacks
    }

    /**
     * An anonymous object implementing the Bluetooth callbacks natively without
     * polluting our Service's public method space.
     */
    private val bluetoothCallbacks = object : BluetoothConnectionController.Callbacks {
        /**
         * Triggered when the smartwatch successfully pairs and connects to us.
         */
        override fun onConnected(message: String) {
            uiCallbacks?.onStatusUpdate(message)
            updateNotification("Connected to Watch")
            
            // As soon as the watch connects, aggressively attempt to connect to the Translation Server!
            if (translationHost != null && translationPort > 0) {
                translationServerClient?.connect(translationHost!!, translationPort)
            }
            uiCallbacks?.onStateChanged()
        }

        /**
         * Triggered when the smartwatch disconnects (walks out of range, or stops recording).
         */
        override fun onDisconnected(message: String) {
            // Tell the python server to finish translating whatever audio it currently has
            translationServerClient?.flush()
            
            uiCallbacks?.onStatusUpdate("$message Waiting for final server transcript...")
            updateNotification("Listening for watch connection...")
            uiCallbacks?.onStateChanged()
        }

        /**
         * Triggered rapidly every time a chunk of raw audio byte data arrives from the watch.
         */
        override fun onAudioPayloadReceived(payload: ByteArray, size: Int) {
            if (translationHost == null || translationPort <= 0) return
            // Instantly forward the raw audio bytes over TCP to the python translation server
            translationServerClient?.sendAudioChunk(translationHost!!, translationPort, payload, size)
        }

        /**
         * Triggered when internal Bluetooth thread states change (e.g. from listening to connected).
         */
        override fun onStateChanged() {
            uiCallbacks?.onStateChanged()
        }

        /**
         * Triggered when the Bluetooth thread wants to show a quick small pop-up to the user.
         */
        override fun showToast(message: String) {
            uiCallbacks?.onToast(message)
        }
    }
    
    /**
     * Updates the text of the un-swipeable background notification.
     */
    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Audio Relay Active")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
            
        val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }
}
