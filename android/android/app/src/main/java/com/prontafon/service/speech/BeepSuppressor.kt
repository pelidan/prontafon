package com.prontafon.service.speech

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Suppresses the system beep sound that plays when SpeechRecognizer.startListening() is called.
 * 
 * The beep can route through different audio streams depending on OEM (Samsung, Pixel, Xiaomi, etc.).
 * This class temporarily mutes all relevant streams and restores them after a delay.
 * 
 * Features:
 * - Volume control muting for MUSIC/NOTIFICATION/ALARM/SYSTEM streams
 * - Uses setStreamVolume(0) to mute streams (ADJUST_MUTE doesn't reliably suppress beeps)
 * - 3-step async chain using configurable timing from SilentRestartTiming:
 *   1. Mute (T+0)
 *   2. Wait for mute propagation (T+50ms default)
 *   3. Execute action (e.g., startListening)
 *   4. Wait for beep suppression window (T+250ms default)
 *   5. Unmute (T+350ms total default)
 * - Handler-based delays ensure proper OS audio mixer synchronization
 * - Thread-safe state management
 * 
 * Usage:
 * ```kotlin
 * beepSuppressor.muteAndExecute {
 *     speechRecognizer?.startListening(intent)
 * }
 * ```
 */
@Singleton
class BeepSuppressor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BeepSuppressor"
        
        // Streams to mute via setStreamVolume(0)
        // These streams cover most beep routing patterns across different OEMs
        private val VOLUME_CONTROLLED_STREAMS = listOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_SYSTEM
        )
    }
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // State management
    private val stateLock = Any()
    private val mutedStreams = mutableSetOf<Int>()
    private val savedVolumes = mutableMapOf<Int, Int>()
    private var isMuted = false
    private var unmuteRunnable: Runnable? = null
    
    /** Whether streams are currently muted. Thread-safe. */
    val isMutedState: Boolean get() = synchronized(stateLock) { isMuted }
    
     /**
      * Immediately mute all audio streams without scheduling any action or unmute.
      * Use this to pre-mute before a cancel() call that would otherwise beep.
      * The subsequent muteAndExecute() call will detect already-muted state and skip re-saving volumes.
      */
     fun muteStreams() {
         synchronized(stateLock) {
             if (isMuted) {
                 Log.d(TAG, "muteStreams: already muted, skipping")
                 return
             }
             
             Log.d(TAG, "muteStreams: immediately muting all streams")
             
             mutedStreams.clear()
             savedVolumes.clear()
             
             VOLUME_CONTROLLED_STREAMS.forEach { streamType ->
                 try {
                     val currentVolume = audioManager.getStreamVolume(streamType)
                     savedVolumes[streamType] = currentVolume
                     audioManager.setStreamVolume(streamType, 0, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
                     mutedStreams.add(streamType)
                     Log.v(TAG, "Stream ${getStreamName(streamType)}: saved=$currentVolume, set to 0")
                 } catch (e: SecurityException) {
                     Log.w(TAG, "SecurityException muting stream ${getStreamName(streamType)}: ${e.message}")
                 } catch (e: Exception) {
                     Log.w(TAG, "Error muting stream ${getStreamName(streamType)}", e)
                 }
             }
             
             isMuted = true
         }
     }
    
      /**
       * Execute a 3-step async chain to suppress beep with proper synchronization:
      * 1. Mute audio streams immediately
      * 2. Wait for mute propagation (Handler.postDelayed, configurable via SilentRestartTiming.mutePropagationDelayMs)
      * 3. Execute the provided action (typically speechRecognizer.startListening())
      * 4. Wait for beep suppression window (Handler.postDelayed, configurable via SilentRestartTiming.beepSuppressionDelayMs)
      * 5. Unmute audio streams
      * 
      * This ensures the OS audio mixer has fully applied the mute before the beep fires.
      * Using Handler.postDelayed (not coroutine delay) guarantees main thread message queue ordering.
      * 
      * @param afterMuteAction The action to execute after mute propagates (e.g., startListening)
      */
     fun muteAndExecute(afterMuteAction: () -> Unit) {
        synchronized(stateLock) {
            // Cancel any pending unmute operation
            unmuteRunnable?.let { mainHandler.removeCallbacks(it) }
            unmuteRunnable = null
            
            if (isMuted) {
                // Already muted (e.g., by muteStreams() in onError) — skip re-saving volumes
                // to avoid saving volume=0 as the "original" volume
                Log.d(TAG, "Already muted, skipping save/mute step, scheduling action + unmute")
            } else {
                Log.d(TAG, "Starting 3-step beep suppression chain")
                
                // STEP 1: Mute volume-controlled streams using setStreamVolume(0)
                mutedStreams.clear()
                savedVolumes.clear()
                
                VOLUME_CONTROLLED_STREAMS.forEach { streamType ->
                    try {
                        val currentVolume = audioManager.getStreamVolume(streamType)
                        savedVolumes[streamType] = currentVolume
                        audioManager.setStreamVolume(streamType, 0, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
                        
                        mutedStreams.add(streamType)
                        Log.v(TAG, "Stream ${getStreamName(streamType)}: saved=$currentVolume, set to 0")
                        
                    } catch (e: SecurityException) {
                        Log.w(TAG, "SecurityException muting stream ${getStreamName(streamType)}: ${e.message}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error muting stream ${getStreamName(streamType)}", e)
                    }
                }
                
                isMuted = true
            }
        }
        
        // STEP 2: Wait for audio HAL to propagate mute (configurable delay) - OUTSIDE LOCK
        mainHandler.postDelayed({
            Log.d(TAG, "Mute propagated (${SilentRestartTiming.mutePropagationDelayMs}ms), executing action (startListening)")
            
            // STEP 3: Execute the action (e.g., startListening)
            try {
                afterMuteAction()
            } catch (e: Exception) {
                Log.e(TAG, "Error executing action after mute", e)
                // Ensure we still unmute on error
                unmute()
                return@postDelayed
            }
            
            // STEP 4: Schedule unmute after beep window (configurable delay from step 3)
            val unmuteCallback = Runnable { 
                Log.d(TAG, "Beep window passed (${SilentRestartTiming.beepSuppressionDelayMs}ms), unmuting")
                performUnmute() 
            }
            synchronized(stateLock) {
                unmuteRunnable = unmuteCallback
            }
            mainHandler.postDelayed(unmuteCallback, SilentRestartTiming.beepSuppressionDelayMs)
            
        }, SilentRestartTiming.mutePropagationDelayMs)
    }
    
    /**
     * Restore saved volumes immediately.
     * Public method for manual cleanup if needed.
     */
    fun unmute() {
        synchronized(stateLock) {
            // Cancel pending unmute if any
            unmuteRunnable?.let { mainHandler.removeCallbacks(it) }
            unmuteRunnable = null
            
            performUnmute()
        }
    }
    
     /**
       * Internal method to restore volumes.
       * Must be called from synchronized block or on main handler.
       */
     private fun performUnmute() {
        synchronized(stateLock) {
            if (!isMuted) {
                Log.d(TAG, "Not muted, nothing to restore")
                return
            }
            
            Log.d(TAG, "Restoring audio stream volumes")
            
            // Restore volume-controlled streams using setStreamVolume
            mutedStreams.forEach { streamType ->
                try {
                    val savedVolume = savedVolumes[streamType] ?: 0
                    audioManager.setStreamVolume(streamType, savedVolume, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
                    Log.v(TAG, "Stream ${getStreamName(streamType)}: restored to $savedVolume")
                    
                } catch (e: SecurityException) {
                    Log.w(TAG, "SecurityException unmuting stream ${getStreamName(streamType)}: ${e.message}")
                } catch (e: Exception) {
                    Log.w(TAG, "Error unmuting stream ${getStreamName(streamType)}", e)
                }
            }
            
            // Clear state
            mutedStreams.clear()
            savedVolumes.clear()
            isMuted = false
            unmuteRunnable = null
        }
    }
    
    /**
     * Get human-readable stream name for logging.
     */
    private fun getStreamName(streamType: Int): String {
        return when (streamType) {
            AudioManager.STREAM_MUSIC -> "MUSIC"
            AudioManager.STREAM_NOTIFICATION -> "NOTIFICATION"
            AudioManager.STREAM_SYSTEM -> "SYSTEM"
            AudioManager.STREAM_ALARM -> "ALARM"
            AudioManager.STREAM_RING -> "RING"
            AudioManager.STREAM_VOICE_CALL -> "VOICE_CALL"
            AudioManager.STREAM_DTMF -> "DTMF"
            else -> "UNKNOWN($streamType)"
        }
    }
    
    /**
     * Clean up resources.
     * Call when the speech recognition manager is destroyed.
     */
    fun cleanup() {
        synchronized(stateLock) {
            Log.d(TAG, "Cleanup - canceling pending unmute and restoring volumes")
            
            // Cancel pending unmute
            unmuteRunnable?.let { mainHandler.removeCallbacks(it) }
            unmuteRunnable = null
            
            // Restore volumes if muted
            if (isMuted) {
                performUnmute()
            }
        }
    }
}
