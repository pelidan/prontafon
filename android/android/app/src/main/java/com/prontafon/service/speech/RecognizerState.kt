package com.prontafon.service.speech

/**
 * State machine for speech recognizer lifecycle.
 * Prevents invalid state transitions and race conditions.
 */
enum class RecognizerState {
    /** Not listening, ready to start */
    IDLE,
    
    /** Transitioning to listening state */
    STARTING,
    
    /** Actively listening for speech */
    LISTENING,
    
    /** Transitioning to idle state */
    STOPPING;
    
    fun canStart(): Boolean = this == IDLE
    fun canStop(): Boolean = this == LISTENING || this == STARTING
    fun isActive(): Boolean = this == LISTENING || this == STARTING
}
