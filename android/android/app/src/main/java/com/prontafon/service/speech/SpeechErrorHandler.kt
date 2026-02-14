package com.prontafon.service.speech

import android.speech.SpeechRecognizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Centralized error classification and handling for speech recognition.
 * 
 * Responsibilities:
 * - Classify errors as transient vs permanent
 * - Provide error recovery strategies
 * - Calculate exponential backoff delays
 * - Generate user-friendly error messages
 */
@Singleton
class SpeechErrorHandler @Inject constructor() {
    
    companion object {
        private const val MAX_RETRY_COUNT = 5
        private val BASE_BACKOFF_DELAY = 1.seconds
        private val MAX_BACKOFF_DELAY = 30.seconds
    }
    
    /**
     * Error classification result.
     */
    data class ErrorClassification(
        val code: Int,
        val message: String,
        val isTransient: Boolean,
        val suggestedRetryDelay: Duration,
        val requiresPermissionCheck: Boolean = false,
        val requiresNetworkCheck: Boolean = false
    )
    
    /**
     * Classify an Android speech recognition error code.
     */
    fun classify(errorCode: Int): ErrorClassification {
        return when (errorCode) {
            // ============ Transient Errors ============
            // These are normal operational states, not real errors
            
            SpeechRecognizer.ERROR_NO_MATCH -> ErrorClassification(
                code = errorCode,
                message = "No speech detected",
                isTransient = true,
                suggestedRetryDelay = Duration.ZERO
            )
            
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ErrorClassification(
                code = errorCode,
                message = "Speech timeout - no audio received",
                isTransient = true,
                suggestedRetryDelay = Duration.ZERO
            )
            
            SpeechRecognizer.ERROR_CLIENT -> ErrorClassification(
                code = errorCode,
                message = "Client-side error",
                isTransient = true,
                suggestedRetryDelay = 100.milliseconds
            )
            
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> ErrorClassification(
                code = errorCode,
                message = "Recognizer busy - please wait",
                isTransient = true,
                suggestedRetryDelay = 1.seconds
            )
            
            // ============ Real Errors ============
            // These require user attention or have recovery implications
            
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ErrorClassification(
                code = errorCode,
                message = "Microphone permission required",
                isTransient = false,
                suggestedRetryDelay = Duration.ZERO, // Don't auto-retry
                requiresPermissionCheck = true
            )
            
            SpeechRecognizer.ERROR_AUDIO -> ErrorClassification(
                code = errorCode,
                message = "Audio recording error - check microphone",
                isTransient = false,
                suggestedRetryDelay = 2.seconds,
                requiresPermissionCheck = true
            )
            
            SpeechRecognizer.ERROR_NETWORK -> ErrorClassification(
                code = errorCode,
                message = "Network unavailable",
                isTransient = false,
                suggestedRetryDelay = 5.seconds,
                requiresNetworkCheck = true
            )
            
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> ErrorClassification(
                code = errorCode,
                message = "Network request timed out",
                isTransient = false,
                suggestedRetryDelay = 5.seconds,
                requiresNetworkCheck = true
            )
            
            SpeechRecognizer.ERROR_SERVER -> ErrorClassification(
                code = errorCode,
                message = "Speech recognition server error",
                isTransient = false,
                suggestedRetryDelay = 5.seconds
            )
            
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> ErrorClassification(
                code = errorCode,
                message = "Disconnected from server",
                isTransient = false,
                suggestedRetryDelay = 5.seconds,
                requiresNetworkCheck = true
            )
            
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> ErrorClassification(
                code = errorCode,
                message = "Too many requests - rate limited",
                isTransient = false,
                suggestedRetryDelay = 30.seconds
            )
            
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> ErrorClassification(
                code = errorCode,
                message = "Selected language not supported",
                isTransient = false,
                suggestedRetryDelay = Duration.ZERO // Don't auto-retry
            )
            
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> ErrorClassification(
                code = errorCode,
                message = "Language temporarily unavailable",
                isTransient = false,
                suggestedRetryDelay = 10.seconds
            )
            
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> ErrorClassification(
                code = errorCode,
                message = "Cannot verify language support",
                isTransient = true,
                suggestedRetryDelay = 2.seconds
            )
            
            else -> ErrorClassification(
                code = errorCode,
                message = "Unknown error (code: $errorCode)",
                isTransient = false,
                suggestedRetryDelay = 2.seconds
            )
        }
    }
    
    /**
     * Get user-friendly error message for display.
     */
    fun getUserMessage(errorCode: Int): String {
        val classification = classify(errorCode)
        return when {
            classification.isTransient -> "" // Don't show transient errors
            classification.requiresPermissionCheck -> "Please grant microphone permission"
            classification.requiresNetworkCheck -> "Check your internet connection"
            else -> classification.message
        }
    }
    
    /**
     * Calculate exponential backoff delay.
     * 
     * Formula: baseDelay * 2^(attemptNumber - 1)
     * Example: 1s, 2s, 4s, 8s, 16s, capped at maxDelay
     * 
     * @param attemptNumber The retry attempt number (1-based)
     * @param baseDelay Base delay for first retry
     * @param maxDelay Maximum delay to cap at
     */
    fun calculateBackoff(
        attemptNumber: Int,
        baseDelay: Duration = BASE_BACKOFF_DELAY,
        maxDelay: Duration = MAX_BACKOFF_DELAY
    ): Duration {
        val multiplier = minOf(attemptNumber, 5)
        val delay = baseDelay * (1 shl (multiplier - 1))
        return minOf(delay, maxDelay)
    }
    
    /**
     * Check if error should trigger auto-restart.
     */
    fun shouldAutoRestart(errorCode: Int, consecutiveErrors: Int): Boolean {
        val classification = classify(errorCode)
        
        // Don't restart if too many consecutive errors
        if (consecutiveErrors >= MAX_RETRY_COUNT) {
            return false
        }
        
        // Don't restart permission errors
        if (classification.requiresPermissionCheck) {
            return false
        }
        
        // Transient errors always restart
        if (classification.isTransient) {
            return true
        }
        
        // Other errors restart with backoff
        return classification.suggestedRetryDelay > Duration.ZERO
    }
}
