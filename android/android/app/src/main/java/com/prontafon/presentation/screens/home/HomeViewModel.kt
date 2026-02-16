package com.prontafon.presentation.screens.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.prontafon.data.repository.PreferencesRepository
import com.prontafon.domain.model.*
import com.prontafon.service.ble.BleConstants
import com.prontafon.service.ble.BleManager
import com.prontafon.service.speech.SpeechRecognitionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * UI state for the Home screen
 */
data class HomeUiState(
    val connectionState: BtConnectionState = BtConnectionState.DISCONNECTED,
    val connectedDevice: BleDeviceInfo? = null,
    val isListening: Boolean = false,
    val currentText: String = "",
    val soundLevel: Float = 0f,
    val errorMessage: String? = null,
    val isInitialized: Boolean = false,
    val showRecognizedText: Boolean = true,
    val isAutoReconnecting: Boolean = false,
    val isDimmed: Boolean = false
)

/**
 * One-time events for the Home screen
 */
sealed interface HomeEvent {
    data class NavigateTo(val route: String) : HomeEvent
    data class ShowSnackbar(val message: String) : HomeEvent
    data object RequestMicrophonePermission : HomeEvent
}

/**
 * ViewModel for the Home screen.
 * Main screen orchestrating speech recognition and BLE communication.
 * 
 * Features:
 * - Speech control (start/stop/pause/resume)
 * - Real-time transcription display
 * - Sound level visualization
 * - Send individual words via BLE
 * - Connection status monitoring
 * - Permission checking
 * 
 * Word Sending Protocol:
 * - Each word is sent individually as a WORD message
 * - Session ID changes when listening starts (new UUID)
 * - Desktop handles command matching and text assembly
 * 
 * Follows MVVM + UDF (Unidirectional Data Flow) pattern:
 * - Immutable UI state exposed via StateFlow
 * - One-time events via Channel/Flow
 * - User actions via public methods
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val bleManager: BleManager,
    private val speechRecognitionManager: SpeechRecognitionManager,
    private val preferencesRepository: PreferencesRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    companion object {
        private const val TAG = "HomeViewModel"
    }
    
    // ==================== UI State ====================
    
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    
    // ==================== One-Time Events ====================
    
    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()
    
    // ==================== Word Queue State ====================
    //
    // Simple word-by-word sending.
    // Desktop handles command matching and text assembly.
    //
    
    // Session ID - new UUID each time listening starts
    private var currentSession = UUID.randomUUID().toString()
    
    // Track how many words have been sent from the current accumulated text
    // This allows repeated words (e.g., "the cat sat on the mat" - "the" appears twice)
    // while still avoiding re-sending on recognition restart.
    private var sentWordCount = 0
    
    // Track the last partial result to detect when it's genuinely new speech
    private var lastPartialText = ""
    
    // Sequential word sending channel - ensures BLE order matches speech order
    private val wordSendChannel = Channel<String>(Channel.UNLIMITED)
    
    // ==================== Initialization ====================
    
    init {
        observeInitialization()
        observeConnectionState()
        observeSpeechState()
        observeSpeechResults()
        
        // Start sequential word sender
        viewModelScope.launch {
            for (word in wordSendChannel) {
                sendWordSequential(word)
            }
        }
    }
    
    // Observe the manager's initialization state
    private fun observeInitialization() {
        viewModelScope.launch {
            speechRecognitionManager.isInitialized.collect { initialized ->
                _uiState.update { it.copy(isInitialized = initialized) }
            }
        }
    }
    
    /**
     * Observe BLE connection state changes
     */
    private fun observeConnectionState() {
        viewModelScope.launch {
            // Track previous state properly to avoid race conditions with other StateFlow collectors.
            // We use a local variable that persists across emissions, rather than reading from
            // _uiState which could be modified by other coroutines between our read and update.
            var previousState: BtConnectionState? = null
            
            bleManager.connectionState.collect { state ->
                // Use the tracked previous state (null on first emission)
                val wasConnected = previousState == BtConnectionState.CONNECTED
                val isNowConnected = state == BtConnectionState.CONNECTED
                
                Log.d(TAG, "Connection state change: $previousState -> $state (wasConnected=$wasConnected, isNowConnected=$isNowConnected)")
                
                // Update UI state (derive isAutoReconnecting from BLE RECONNECTING state)
                _uiState.update { 
                    it.copy(
                        connectionState = state,
                        isAutoReconnecting = state == BtConnectionState.RECONNECTING
                    ) 
                }
                
                // Stop listening when connection is lost - no point listening if we can't send words
                // This must happen BEFORE clearing sentWordCount, so we don't lose track of what was sent
                if (wasConnected && !isNowConnected) {
                    Log.d(TAG, "Connection lost (was CONNECTED, now $state) - stopping speech recognition")
                    stopListening()
                    // Also clear word tracking immediately when disconnecting
                    // This ensures clean state even if reconnect happens quickly
                    sentWordCount = 0
                    lastPartialText = ""
                    Log.d(TAG, "Cleared word tracking state on disconnect")
                }
                
                // Clear word tracking state when connection is established/re-established
                // This prevents stale sentWordCount from blocking new words after reconnect
                // Note: This handles the case where we reconnect without going through stopListening
                // (e.g., if disconnect detection was delayed)
                if (isNowConnected && previousState != null && previousState != BtConnectionState.CONNECTED) {
                    Log.d(TAG, "Connection established (was $previousState), clearing word tracking state")
                    sentWordCount = 0
                    lastPartialText = ""
                    // Note: Don't reset session/sequence here - let startListening handle that
                    // This only clears the "already sent" tracking to allow re-sending words
                }
                
                // Update tracked previous state for next emission
                previousState = state
            }
        }
        
        viewModelScope.launch {
            bleManager.connectedDevice.collect { device ->
                _uiState.update { 
                    it.copy(connectedDevice = device) 
                }
            }
        }
    }
    
    /**
     * Observe speech recognition state changes
     */
    private fun observeSpeechState() {
        viewModelScope.launch {
            speechRecognitionManager.isListening.collect { listening ->
                _uiState.update { 
                    it.copy(isListening = listening) 
                }
            }
        }
        
        // Note: currentText observation moved to observeSpeechResults() 
        // to combine with showPartialResults preference
        
        viewModelScope.launch {
            speechRecognitionManager.soundLevel.collect { level ->
                _uiState.update { 
                    it.copy(soundLevel = level) 
                }
            }
        }
        
        viewModelScope.launch {
            speechRecognitionManager.errorMessage.collect { error ->
                _uiState.update { 
                    it.copy(errorMessage = error) 
                }
            }
        }
    }
    
    /**
     * Observe recognized speech and send words via BLE.
     * 
     * Each word is sent individually with a sequence number.
     * Desktop handles command matching and text assembly.
     * 
     * Note: Partial results are ALWAYS sent over BLE regardless of UI preference.
     * The showRecognizedText preference only controls whether they're displayed in the UI.
     */
    private fun observeSpeechResults() {
        // Handle partial results (word-by-word sending)
        // ALWAYS send over BLE regardless of preference
        viewModelScope.launch {
            speechRecognitionManager.partialResults.collect { text ->
                handlePartialResult(text)
            }
        }
        
        // Observe showRecognizedText preference for UI control
        viewModelScope.launch {
            preferencesRepository.showRecognizedText.collect { show ->
                _uiState.update { 
                    it.copy(showRecognizedText = show) 
                }
            }
        }
        
        // Observe current text for UI display
        viewModelScope.launch {
            speechRecognitionManager.currentText.collect { text ->
                _uiState.update { 
                    it.copy(currentText = text) 
                }
            }
        }
        
        // Handle final recognized text
        viewModelScope.launch {
            speechRecognitionManager.recognizedText.collect { text ->
                if (text.isNotBlank()) {
                    handleFinalResult(text)
                    // Always update UI with final results regardless of preference
                    _uiState.update { 
                        it.copy(currentText = text) 
                    }
                }
            }
        }
        
        // Note: We ignore recognizedCommand - desktop handles all command matching now
    }
    
    /**
     * Handle partial speech recognition result.
     * 
     * Approach: Split into words, send words at positions we haven't sent yet.
     * We track the count of sent words, not word content, so repeated words work.
     * 
     * Example: User says "the cat sat on the mat"
     * - Partial 1: "the" → 1 word, sentWordCount=0, send "the", sentWordCount=1
     * - Partial 2: "the cat" → 2 words, sentWordCount=1, send "cat", sentWordCount=2
     * - Partial 3: "the cat sat on the mat" → 6 words, sentWordCount=2, send "sat","on","the","mat", sentWordCount=6
     * 
     * Recognition restart handling:
     * - If text no longer starts with what we had before, reset sentWordCount
     * - This handles error 7 (no speech) restarts
     */
    private fun handlePartialResult(text: String) {
        if (!isConnected() || text.isBlank()) return
        
        // Split into words
        val words = splitIntoWords(text)
        
        // Check if this looks like genuinely new speech (text doesn't start with last partial)
        // This helps detect when user starts a completely new utterance
        // Use case-insensitive comparison to avoid false resets on capitalization changes
        val isNewUtterance = lastPartialText.isNotEmpty() && 
            !text.lowercase().startsWith(lastPartialText.take(lastPartialText.length / 2 + 1).lowercase())
        
        if (isNewUtterance) {
            // User started speaking something completely different - reset tracking
            Log.d(TAG, "New utterance detected, resetting sent word count. Old: '$lastPartialText', New: '$text'")
            sentWordCount = 0
        }
        
        lastPartialText = text
        
        // Send words at positions we haven't sent yet
        // This allows repeated words like "the" to be sent multiple times
        for (i in sentWordCount until words.size) {
            sendWord(words[i])
        }
        sentWordCount = words.size
    }
    
    /**
     * Handle final speech recognition result.
     * 
     * Final results from Google's speech recognition often differ from partial results
     * due to post-processing (e.g., converting number words to digits: "čtvrtá" → "4").
     * Since partial results already sent all words in real-time, we don't send final
     * result corrections to avoid duplicates and preserve what the user actually spoke.
     * 
     * We only update tracking state to keep sentWordCount consistent with the final
     * word count, in case recognition restarts.
     */
    private fun handleFinalResult(text: String) {
        if (!isConnected() || text.isBlank()) return
        
        val finalWords = splitIntoWords(text)
        val partialWords = splitIntoWords(lastPartialText)
        
        // Only update sentWordCount to final word count for consistency.
        // Don't send any words - partials already sent everything in real-time.
        // Google's final results often rearrange words (e.g., "Kikinu" -> "k nim"),
        // making word-count-based "new word" detection unreliable and causing duplicates.
        sentWordCount = finalWords.size
        
        // Log if final differs from partial (for debugging)
        if (finalWords != partialWords) {
            Log.d(TAG, "Final result differs from partial: final='$text', partial='$lastPartialText'")
        }
        
        // Note: We intentionally do NOT reset sentWordCount here.
        // Android speech recognition auto-restarts and may produce overlapping results.
        // sentWordCount is only reset when:
        // 1. A new session starts (startListening)
        // 2. A genuinely new utterance is detected (in handlePartialResult)
    }
    
    /**
     * Queue a word for sequential sending.
     * Words are sent in strict order via the wordSendChannel.
     */
    private fun sendWord(word: String) {
        wordSendChannel.trySend(word)
    }
    
    /**
     * Send a single word with retry logic (internal, called by channel consumer).
     * Retries up to 2 more times if the initial send fails.
     */
    private suspend fun sendWordSequential(word: String) {
        var success = false
        var attempts = 0
        val maxAttempts = 3
        
        while (!success && attempts < maxAttempts) {
            attempts++
            val message = Message.word(word, currentSession)
            success = bleManager.sendMessage(message)
            
            if (success) {
                Log.d(TAG, "Sent word: '$word' session=$currentSession (attempt $attempts)")
            } else if (attempts < maxAttempts) {
                Log.w(TAG, "Failed to send word: '$word' (attempt $attempts/$maxAttempts), retrying...")
                delay(BleConstants.RETRY_DELAY_MS)
            }
        }
        
        if (!success) {
            Log.e(TAG, "Failed to send word: '$word' after $maxAttempts attempts")
        }
    }
    
    // ==================== Speech Control Actions ====================
    
    /**
     * Start listening for speech.
     * Creates a new session.
     */
    fun startListening() {
        viewModelScope.launch {
            if (!canListen()) {
                _events.send(HomeEvent.ShowSnackbar("Connect to a device first"))
                return@launch
            }
            
            // Check if we have microphone permission
            if (!speechRecognitionManager.hasMicrophonePermission()) {
                _events.send(HomeEvent.RequestMicrophonePermission)
                return@launch
            }
            
            // New session - reset all word tracking state
            currentSession = UUID.randomUUID().toString()
            sentWordCount = 0
            lastPartialText = ""
            
            Log.d(TAG, "Starting new session: $currentSession")
            
            // Direct call to manager for foreground-only mode
            speechRecognitionManager.startListening()
        }
    }
    
    /**
     * Stop listening for speech
     */
    fun stopListening() {
        viewModelScope.launch {
            speechRecognitionManager.stopListening()
        }
    }
    
    /**
     * Toggle listening state
     */
    fun toggleListening() {
        viewModelScope.launch {
            if (_uiState.value.isListening) {
                stopListening()
            } else {
                startListening()
            }
        }
    }
    
    // ==================== Connection Actions ====================
    
    /**
     * Disconnect from the currently connected device
     */
    fun disconnect() {
        viewModelScope.launch {
            bleManager.disconnect()
        }
    }
    
    /**
     * Cancel auto-reconnect attempt
     */
    fun cancelAutoReconnect() {
        viewModelScope.launch {
            Log.d(TAG, "Cancelling auto-reconnect")
            _uiState.update { it.copy(isAutoReconnecting = false) }
            bleManager.disconnect()
        }
    }
    
    // ==================== UI Actions ====================
    
    /**
     * Toggle screen dimming state
     */
    fun toggleDim() {
        _uiState.update { it.copy(isDimmed = !it.isDimmed) }
    }
    
    // ==================== Error Handling ====================
    
    /**
     * Clear current error message
     */
    fun clearError() {
        speechRecognitionManager.clearError()
        _uiState.update { it.copy(errorMessage = null) }
    }
    
    // ==================== State Checks ====================
    
    /**
     * Check if speech listening is allowed
     */
    private fun canListen(): Boolean {
        return isConnected() && _uiState.value.isInitialized
    }
    
    /**
     * Check if connected to BLE device
     */
    private fun isConnected(): Boolean {
        return _uiState.value.connectionState == BtConnectionState.CONNECTED
    }
    
    /**
     * Helper function to split text into words
     */
    private fun splitIntoWords(text: String): List<String> =
        text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    
    // ==================== Lifecycle ====================
    
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "onCleared: stopping speech recognition")
        try {
            speechRecognitionManager.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech recognition in onCleared", e)
        }
    }
}
