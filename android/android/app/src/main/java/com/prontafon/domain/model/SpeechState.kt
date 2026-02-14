package com.prontafon.domain.model

/**
 * Sealed class representing the state of speech recognition.
 * Provides type-safe state management with associated data.
 */
sealed class SpeechState {
    /**
     * Speech recognition is idle/inactive
     */
    data object Idle : SpeechState()

    /**
     * Speech recognition is starting up
     */
    data object Starting : SpeechState()

    /**
     * Speech recognition is actively listening
     * @param partialResult Partial transcription result (if available)
     */
    data class Listening(val partialResult: String? = null) : SpeechState()

    /**
     * Speech recognition is stopping
     */
    data object Stopping : SpeechState()

    /**
     * Speech recognition encountered an error
     * @param error Error message describing what went wrong
     * @param errorCode Optional error code for categorization
     */
    data class Error(val error: String, val errorCode: Int? = null) : SpeechState()

    /**
     * Whether speech recognition is currently active
     */
    val isActive: Boolean
        get() = this is Starting || this is Listening || this is Stopping

    /**
     * Whether speech recognition can be started
     */
    val canStart: Boolean
        get() = this is Idle || this is Error

    /**
     * Whether speech recognition can be stopped
     */
    val canStop: Boolean
        get() = this is Starting || this is Listening

    /**
     * Human-readable display text for the current state
     */
    val displayText: String
        get() = when (this) {
            is Idle -> "Tap to speak"
            is Starting -> "Starting..."
            is Listening -> partialResult ?: "Listening..."
            is Stopping -> "Stopping..."
            is Error -> "Error: $error"
        }
}
