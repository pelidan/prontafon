package com.prontafon.service

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.prontafon.di.modules.ApplicationScope
import com.prontafon.service.speech.SpeechRecognitionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors audio mode by polling to detect both cellular and VoIP calls.
 * 
 * Features:
 * - Detects cellular calls (traditional phone)
 * - Detects VoIP calls (WhatsApp, Telegram, Signal, etc.)
 * - Stops speech recognition when any call is active
 * - No special permissions required (READ_PHONE_STATE not needed)
 * - Uses polling instead of audio focus to avoid conflicts with SpeechRecognizer
 */
@Singleton
class AudioFocusObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val speechRecognitionManager: SpeechRecognitionManager,
    @ApplicationScope private val externalScope: CoroutineScope
) {
    companion object {
        private const val TAG = "AudioFocusObserver"
        private const val POLLING_INTERVAL_MS = 1000L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var pollingJob: Job? = null
    private var wasInCall = false
    
    @Volatile
    private var isRegistered = false

    /**
     * Start polling audio mode to detect calls.
     * Polls every second and stops speech recognition when entering call mode.
     */
    fun register() {
        if (isRegistered) {
            Log.d(TAG, "Already registered")
            return
        }

        try {
            // Start polling audio mode
            pollingJob = externalScope.launch {
                while (isActive) {
                    checkAudioMode()
                    delay(POLLING_INTERVAL_MS)
                }
            }
            
            isRegistered = true
            Log.d(TAG, "Audio mode observer registered (polling every ${POLLING_INTERVAL_MS}ms)")
            Log.d(TAG, "Initial audio mode: ${getAudioModeString()}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio mode observer", e)
        }
    }

    /**
     * Stop polling audio mode.
     */
    fun unregister() {
        if (!isRegistered) {
            return
        }

        try {
            pollingJob?.cancel()
            pollingJob = null
            wasInCall = false
            isRegistered = false
            Log.d(TAG, "Audio mode observer unregistered")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister audio mode observer", e)
        }
    }

    /**
     * Check current audio mode and stop speech recognition if call detected.
     */
    private suspend fun checkAudioMode() {
        val mode = audioManager.mode
        val inCall = (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION)
        
        if (inCall && !wasInCall) {
            // Entered call mode
            Log.d(TAG, "Entered call mode: ${getAudioModeString()}")
            stopSpeechRecognition("Device in call mode")
            wasInCall = true
        } else if (!inCall && wasInCall) {
            // Exited call mode
            Log.d(TAG, "Exited call mode: ${getAudioModeString()}")
            wasInCall = false
        }
    }

    /**
     * Check if device is currently in a call.
     * 
     * AudioManager.MODE_IN_CALL: Cellular call active
     * AudioManager.MODE_IN_COMMUNICATION: VoIP call active (WhatsApp, Telegram, etc.)
     */
    fun isInCall(): Boolean {
        val mode = audioManager.mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    /**
     * Get current audio mode as a string for logging.
     */
    private fun getAudioModeString(): String {
        return when (audioManager.mode) {
            AudioManager.MODE_NORMAL -> "NORMAL"
            AudioManager.MODE_RINGTONE -> "RINGTONE"
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            else -> "UNKNOWN(${audioManager.mode})"
        }
    }

    /**
     * Stop speech recognition with a reason.
     */
    private fun stopSpeechRecognition(reason: String) {
        Log.d(TAG, "Stopping speech recognition: $reason")
        externalScope.launch {
            speechRecognitionManager.stopListening()
        }
    }
}
