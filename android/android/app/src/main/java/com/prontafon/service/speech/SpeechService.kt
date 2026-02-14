package com.prontafon.service.speech

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.prontafon.R
import com.prontafon.MainActivity
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Foreground service for continuous speech recognition.
 * 
 * Features:
 * - Runs as foreground service with notification
 * - Integrates SpeechRecognitionManager
 * - Integrates with BLE manager for message transmission
 * - Handles service commands (START, STOP, PAUSE, RESUME)
 * - Automatic cleanup on service destruction
 * 
 * Note: Manual dependency injection used instead of Hilt due to Service limitations
 */
class SpeechService : Service() {
    
    /**
     * Hilt entry point for accessing dependencies from non-Hilt injected service
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SpeechServiceEntryPoint {
        fun speechRecognitionManager(): SpeechRecognitionManager
    }
    
    companion object {
        private const val TAG = "SpeechService"
        
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "speech_recognition_channel"
        private const val CHANNEL_NAME = "Speech Recognition"
        
        // Service actions
        const val ACTION_START_LISTENING = "com.prontafon.ACTION_START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.prontafon.ACTION_STOP_LISTENING"
        const val ACTION_PAUSE_LISTENING = "com.prontafon.ACTION_PAUSE_LISTENING"
        const val ACTION_RESUME_LISTENING = "com.prontafon.ACTION_RESUME_LISTENING"
        const val ACTION_STOP_SERVICE = "com.prontafon.ACTION_STOP_SERVICE"
        const val ACTION_FORCE_STOP_SERVICE = "com.prontafon.ACTION_FORCE_STOP_SERVICE"
        
        // PendingIntent request codes - must be unique
        private const val REQUEST_CODE_CONTENT = 100
        private const val REQUEST_CODE_ACTION = 101
        private const val REQUEST_CODE_STOP = 102
        private const val REQUEST_CODE_START = 103
        
        // Service running state - observable by ViewModels
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
        
        /**
         * Start the speech service
         */
        fun start(context: Context) {
            val intent = Intent(context, SpeechService::class.java)
            intent.action = ACTION_START_LISTENING
            context.startForegroundService(intent)
        }
        
        /**
         * Stop the speech service.
         * Uses stopService() to avoid creating a new service instance if already stopped.
         */
        fun stop(context: Context) {
            val intent = Intent(context, SpeechService::class.java)
            context.stopService(intent)
        }
    }
    
    private lateinit var speechRecognitionManager: SpeechRecognitionManager
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val binder = LocalBinder()
    
    private var wakeLock: PowerManager.WakeLock? = null
    
    /**
     * Binder for local service binding
     */
    inner class LocalBinder : Binder() {
        fun getService(): SpeechService = this@SpeechService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Speech service created")
        
        // Get dependencies from Hilt
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            SpeechServiceEntryPoint::class.java
        )
        speechRecognitionManager = entryPoint.speechRecognitionManager()
        
        // Create notification channel
        createNotificationChannel()
        
        // Start as foreground service
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(isListening = false, isPaused = false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification(isListening = false, isPaused = false))
        }
        
        // Setup flow observations
        setupFlows()
        Log.d(TAG, "Speech service created successfully")
        _isRunning.value = true
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_LISTENING -> {
                Log.d(TAG, "ACTION_START_LISTENING")
                acquireWakeLock()
                serviceScope.launch {
                    speechRecognitionManager.startListening()
                }
            }
            ACTION_STOP_LISTENING -> {
                Log.d(TAG, "ACTION_STOP_LISTENING received")
                serviceScope.launch {
                    speechRecognitionManager.stopListening()
                }
            }
            ACTION_PAUSE_LISTENING -> {
                Log.d(TAG, "ACTION_PAUSE_LISTENING received")
                serviceScope.launch {
                    speechRecognitionManager.pauseListening()
                }
            }
            ACTION_RESUME_LISTENING -> {
                Log.d(TAG, "ACTION_RESUME_LISTENING received")
                serviceScope.launch {
                    speechRecognitionManager.resumeListening()
                }
            }
            ACTION_STOP_SERVICE, ACTION_FORCE_STOP_SERVICE -> {
                Log.d(TAG, "ACTION_STOP_SERVICE or ACTION_FORCE_STOP_SERVICE received")
                // Let onDestroy handle stopListening() to avoid race with serviceScope.cancel()
                releaseWakeLock()
                stopSelf()
            }
            else -> {
                Log.w(TAG, "Unknown action received: ${intent?.action}")
            }
        }
        
        return START_REDELIVER_INTENT
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Speech service destroyed")
        
        // Non-blocking cleanup
        try {
            speechRecognitionManager.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech recognition in onDestroy", e)
        }
        
        releaseWakeLock()
        serviceScope.cancel()
        _isRunning.value = false
        super.onDestroy()
    }
    
    // ==================== Private Methods ====================
    
    private fun setupFlows() {
        // Listen to speech recognition state changes and update notification
        combine(
            speechRecognitionManager.isListening,
            speechRecognitionManager.isPaused
        ) { isListening, isPaused ->
            Pair(isListening, isPaused)
        }
            .onEach { (isListening, isPaused) ->
                // Update notification
                val notification = createNotification(isListening, isPaused)
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    notificationManager.notify(NOTIFICATION_ID, notification)
                }
            }
            .launchIn(serviceScope)
        
        // Note: BLE message sending is handled by HomeViewModel
        // SpeechService only manages the foreground service lifecycle
        // This avoids duplicate messages being sent (HomeViewModel already sends words/commands)
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing speech recognition"
            setShowBadge(false)
        }
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
    
    private fun createNotification(isListening: Boolean, isPaused: Boolean): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_CONTENT,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Stop action - always force stop
        val stopIntent = Intent(this, SpeechService::class.java).apply {
            action = ACTION_FORCE_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build notification based on state
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Prontafon")
            .setSmallIcon(R.drawable.ic_microphone)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        
        // Show contextual buttons based on state
        if (isListening && !isPaused) {
            // Listening and not paused: Show [Pause] [Stop]
            val pauseIntent = Intent(this, SpeechService::class.java).apply {
                action = ACTION_PAUSE_LISTENING
            }
            val pausePendingIntent = PendingIntent.getService(
                this,
                REQUEST_CODE_ACTION,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentText("Listening for speech...")
                .addAction(0, "Pause", pausePendingIntent)
                .addAction(0, "Stop", stopPendingIntent)
        } else {
            // Paused OR (not listening and not paused): Show [Start] [Stop]
            val startIntent = Intent(this, SpeechService::class.java).apply {
                action = ACTION_RESUME_LISTENING
            }
            val startPendingIntent = PendingIntent.getService(
                this,
                REQUEST_CODE_START,
                startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val statusText = if (isPaused) "Speech recognition paused" else "Speech recognition ready"
            builder.setContentText(statusText)
                .addAction(0, "Start", startPendingIntent)
                .addAction(0, "Stop", stopPendingIntent)
        }
        
        return builder.build()
    }
    
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "prontafon:speech_recognition"
            )
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(4 * 60 * 60 * 1000L)  // 4 hour timeout
            Log.d(TAG, "WakeLock acquired for background mode")
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }
}
