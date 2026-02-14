package com.prontafon.service.speech

/**
 * Centralized timing configuration for "Silent Restart Loop" beep suppression.
 * 
 * The Golden Path Timeline:
 * - T+0ms:   Mute audio streams (save originals, set to 0)
 * - T+50ms:  Cancel & Destroy (Handler.postDelayed)
 * - T+100ms: Create & Start (Handler.postDelayed, 50ms after cancel)
 * - T+400ms: Unmute (Handler.postDelayed, 300ms after start)
 * 
 * All timing values are configurable to support per-device/OEM tuning.
 */
object SilentRestartTiming {
    
    /**
     * T+0 -> T+50ms: Delay for audio HAL to propagate mute before cancel/destroy.
     * 
     * Requirement: Audio mixer must have fully applied mute before any cancel/destroy
     * calls that could trigger beeps.
     * 
     * Default: 50ms (Golden Path)
     * Range: 30-150ms depending on hardware audio latency
     */
    @Volatile var mutePropagationDelayMs: Long = 50L
    
    /**
     * T+50ms -> T+100ms: Delay for OS to release microphone lock after destroy.
     * 
     * Requirement: OS needs time to clean up destroyed SpeechRecognizer instance
     * and release the microphone resource before a new instance can acquire it.
     * 
     * Default: 50ms (Golden Path)
     * Range: 30-100ms depending on OS scheduler
     */
    @Volatile var hardwareReleaseDelayMs: Long = 50L
    
    /**
     * T+100ms -> T+400ms: Delay for beep to finish playing into silence before unmute.
     * 
     * Requirement: System plays "Start Listening" beep immediately after startListening().
     * We must wait for this beep to complete playing into the muted audio stream.
     * 
     * Default: 300ms (Golden Path)
     * Range: 200-400ms depending on OEM beep duration
     */
    @Volatile var beepSuppressionDelayMs: Long = 300L
    
    /**
     * Total muted window: T+0 to T+400ms = 400ms default.
     * Calculated as: mutePropagationDelayMs + hardwareReleaseDelayMs + beepSuppressionDelayMs
     */
    val totalMutedWindowMs: Long
        get() = mutePropagationDelayMs + hardwareReleaseDelayMs + beepSuppressionDelayMs
    
    /**
     * Alternative: Conservative timing for devices with slower audio HAL or longer beeps.
     * Use this preset if the default Golden Path values cause audible artifacts.
     */
    fun useConservativeTiming() {
        mutePropagationDelayMs = 150L
        hardwareReleaseDelayMs = 100L
        beepSuppressionDelayMs = 400L
        // Total: 650ms
    }
    
    /**
     * Alternative: Aggressive timing for devices with fast audio processing.
     * Use with caution - test thoroughly on target device.
     */
    fun useAggressiveTiming() {
        mutePropagationDelayMs = 30L
        hardwareReleaseDelayMs = 30L
        beepSuppressionDelayMs = 200L
        // Total: 260ms
    }
    
    /**
     * Reset to Golden Path defaults.
     */
    fun useGoldenPathTiming() {
        mutePropagationDelayMs = 50L
        hardwareReleaseDelayMs = 50L
        beepSuppressionDelayMs = 300L
        // Total: 400ms
    }
}
