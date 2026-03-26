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

class AudioServerService : Service(), BluetoothConnectionController.Callbacks {

    private val tag = "AudioServerService"
    private val channelId = "AudioServerServiceChannel"
    private val notificationId = 1
    
    // Core controllers
    private var connectionController: BluetoothConnectionController? = null
    private var translationServerClient: TranslationServerClient? = null
    
    private val appUuid = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    private val serviceName = "audiorecorderphone"
    
    // Binder for UI
    private val binder = LocalBinder(this)
    
    // Callbacks to UI
    interface UiCallbacks {
        fun onStatusUpdate(message: String)
        fun onTranscriptSet(message: String)
        fun onTranscriptAppended(message: String)
        fun onToast(message: String)
        fun onStateChanged()
    }
    
    private var uiCallbacks: UiCallbacks? = null
    
    private var translationHost: String? = null
    private var translationPort: Int = 0

    class LocalBinder(private val service: AudioServerService) : Binder() {
        fun getService(): AudioServerService = service
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        
        connectionController = BluetoothConnectionController(
            bluetoothAdapter = bluetoothAdapter,
            logTag = tag,
            appUuid = appUuid,
            serviceName = serviceName,
            callbacks = this
        )
        
        translationServerClient = TranslationServerClient(
            logTag = tag,
            updateStatus = { msg -> uiCallbacks?.onStatusUpdate(msg) },
            setTranscript = { msg -> 
                uiCallbacks?.onTranscriptSet(msg)
                connectionController?.sendTextChunk(msg)
            },
            appendTranscript = { msg -> 
                uiCallbacks?.onTranscriptAppended(msg)
                connectionController?.sendTextChunk(msg)
            },
            showToast = { msg -> uiCallbacks?.onToast(msg) }
        )
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = intent?.getStringExtra("EXTRA_HOST")
        val port = intent?.getIntExtra("EXTRA_PORT", 0) ?: 0
        
        if (host != null && port > 0) {
            translationHost = host
            translationPort = port
        }
        
        startForegroundServiceNotification()
        
        // Start bluetooth listening
        connectionController?.startServer()
        uiCallbacks?.onStatusUpdate("Server started in background. Ongoing via notification.")
        uiCallbacks?.onStateChanged()
        
        return START_NOT_STICKY
    }
    
    private fun startForegroundServiceNotification() {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Audio Relay Active")
            .setContentText("Listening for watch connection...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelId,
                "Background Audio Relay",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        connectionController?.stopAll()
        translationServerClient?.release()
        super.onDestroy()
    }
    
    fun setUiCallbacks(callbacks: UiCallbacks?) {
        this.uiCallbacks = callbacks
    }

    // --- BluetoothConnectionController.Callbacks ---
    
    override fun onConnected(message: String) {
        uiCallbacks?.onStatusUpdate(message)
        updateNotification("Connected to Watch")
        
        if (translationHost != null && translationPort > 0) {
            translationServerClient?.connect(translationHost!!, translationPort)
        }
        uiCallbacks?.onStateChanged()
    }

    override fun onDisconnected(message: String) {
        translationServerClient?.flush()
        uiCallbacks?.onStatusUpdate("$message Waiting for final server transcript...")
        updateNotification("Listening for watch connection...")
        uiCallbacks?.onStateChanged()
    }

    override fun onAudioPayloadReceived(payload: ByteArray, size: Int) {
        if (translationHost == null || translationPort <= 0) return
        translationServerClient?.sendAudioChunk(translationHost!!, translationPort, payload, size)
    }

    override fun onStateChanged() {
        uiCallbacks?.onStateChanged()
    }

    override fun showToast(message: String) {
        uiCallbacks?.onToast(message)
    }
    
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
