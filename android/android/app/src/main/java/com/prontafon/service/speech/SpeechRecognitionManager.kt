package com.prontafon.service.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.prontafon.domain.model.CommandCode
import com.prontafon.domain.model.CommandParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes

/**
 * Manages Android SpeechRecognizer with state machine, error recovery, and auto-restart.
 * 
 * Features:
 * - State machine: IDLE → STARTING → LISTENING → STOPPING
 * - Auto-restart on transient errors
 * - Exponential backoff on repeated failures
 * - Watchdog timer for stuck states (20s timeout)
 * - Emits state via StateFlow
 * - Emits results via SharedFlow
 */
@Singleton
class SpeechRecognitionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val errorHandler: SpeechErrorHandler,
    private val beepSuppressor: BeepSuppressor
) {
    companion object {
        private const val TAG = "SpeechRecognitionMgr"
        
         // Silence timer configuration (beep prevention)
        // Set to 4s to proactively restart before Google's ~5s ERROR_NO_MATCH timeout
        private val SILENCE_TIMEOUT = 4.seconds
        
        // Stuck state detection
        private val STUCK_STATE_TIMEOUT = 10.seconds
        
        // Error recovery configuration
        private const val MAX_CONSECUTIVE_ERRORS = 5
        
        // Default listening configuration
        private val DEFAULT_PAUSE_FOR = 10.seconds
        
        // Segmented session memory management
        // After MAX_SEGMENTS, perform a hard restart to clear memory
        private const val MAX_SEGMENTS_BEFORE_RESTART = 100
        // Time-based hard restart threshold for multi-hour sessions
        private val FULL_RESTART_TIME_THRESHOLD = 30.minutes
    }
    
    // Speech recognizer - created lazily on main thread
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    
    // Coroutine scope for background operations
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // State machine
    @Volatile
    private var recognizerState = RecognizerState.IDLE
    private val stateLock = Any()
    
    // Error handling
    private var consecutiveErrors = 0
    private var restartScheduled = false
    private var restartJob: Job? = null
    
    // Segmented session tracking for memory management
    private var segmentCount = 0
    private var lastFullRestartTime: Long = System.currentTimeMillis()
    
    // Flag to prevent restart when stop was explicitly requested
    @Volatile
    private var stopRequested = false
    
    // Flag to indicate we're in the middle of a scheduled restart (memory management)
    @Volatile
    private var isRestarting = false
    
    // Early mute tracking for success beep suppression
    @Volatile
    private var isMutedForProcessing = false
    private var earlyMuteTimeoutRunnable: Runnable? = null
    
    // Silence timer for beep prevention
    private var silenceTimerJob: Job? = null
    private var lastStateChange: Long = System.currentTimeMillis()
    
    // Configuration
    private var pauseFor: Duration = DEFAULT_PAUSE_FOR
    private var autoRestart: Boolean = true
    
    // ==================== Public State Flows ====================
    
    private val _recognizerStateFlow = MutableStateFlow(RecognizerState.IDLE)
    
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()
    
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()
    
    private val _currentText = MutableStateFlow("")
    val currentText: StateFlow<String> = _currentText.asStateFlow()
    
    private val _soundLevel = MutableStateFlow(0f)
    val soundLevel: StateFlow<Float> = _soundLevel.asStateFlow()
    
    private val _selectedLocale = MutableStateFlow("cs-CZ")
    val selectedLocale: StateFlow<String> = _selectedLocale.asStateFlow()
    
    private val _availableLocales = MutableStateFlow<List<Locale>>(emptyList())
    val availableLocales: StateFlow<List<Locale>> = _availableLocales.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    
    // SharedFlows for events
    private val _recognizedText = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val recognizedText: SharedFlow<String> = _recognizedText.asSharedFlow()
    
    private val _recognizedCommand = MutableSharedFlow<CommandCode>(extraBufferCapacity = 10)
    val recognizedCommand: SharedFlow<CommandCode> = _recognizedCommand.asSharedFlow()
    
    private val _partialResults = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val partialResults: SharedFlow<String> = _partialResults.asSharedFlow()
    
    // ==================== Lifecycle Methods ====================
    
    /**
     * Check if microphone permission is granted.
     */
    fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Initialize speech recognition service.
     * Must be called before any other methods.
     * @return true if initialization successful
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.Main) {
        Log.d(TAG, "Initializing speech recognition manager")
        
        if (_isInitialized.value) {
            Log.d(TAG, "Already initialized")
            return@withContext true
        }
        
        // Check if speech recognition is available
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device")
            _errorMessage.value = "Speech recognition not available"
            return@withContext false
        }
        
        try {
            // Create recognizer on main thread
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createRecognitionListener())
            }
            
            // Query available locales
            loadAvailableLocales()
            
            _isInitialized.value = true
            Log.d(TAG, "Speech recognition manager initialized successfully")
            return@withContext true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize speech recognition manager", e)
            _errorMessage.value = "Failed to initialize: ${e.message}"
            return@withContext false
        }
    }
    
    /**
     * Start listening for speech.
     * @param isRestart true if this is an auto-restart (keeps UI stable)
     * @return true if the start sequence was initiated successfully, false if permission not granted.
     *         Note: When isRestart=true, returns true before the recognizer actually starts 
     *         (the actual start happens ~50ms later via Handler callback). Callers should observe
     *         state flows (isListening, errorMessage) for definitive status rather than relying 
     *         on this return value.
     */
    suspend fun startListening(isRestart: Boolean = false): Boolean {
        Log.d(TAG, "startListening() called, current state: $recognizerState, isRestart: $isRestart")
        
        // Clear the stop flag when explicitly starting (not on restart)
        if (!isRestart) {
            stopRequested = false
            isRestarting = false
        }
        
        if (!_isInitialized.value) {
            Log.w(TAG, "Cannot start - not initialized")
            return false
        }
        
        // Check microphone permission before starting
        if (!hasMicrophonePermission()) {
            Log.w(TAG, "Cannot start - microphone permission not granted")
            _errorMessage.value = "Microphone permission required"
            return false
        }
        
        if (_isPaused.value) {
            Log.d(TAG, "Resuming from paused state")
            _isPaused.value = false
        }
        
        synchronized(stateLock) {
            if (!recognizerState.canStart()) {
                Log.w(TAG, "Cannot start from state: $recognizerState")
                return false
            }
            transitionState(RecognizerState.STARTING)
        }
        
        val intent = createRecognizerIntent()
        
        // Suppress system beep sound ONLY on automatic restarts
        // Let the beep play on manual user actions (button press) for audio feedback
        if (isRestart) {
            Log.d(TAG, "Auto-restart: Suppressing beep with 3-step async chain")
            
            // Use 3-step async chain: mute → wait 500ms → startListening → wait 700ms → unmute
            // This ensures the audio HAL has fully applied the mute before the beep fires
            beepSuppressor.muteAndExecute {
                try {
                    speechRecognizer?.startListening(intent)
                    Log.d(TAG, "Started listening with locale: ${_selectedLocale.value}, isRestart=true")
                    // Note: silenceTimer will be started in onReadyForSpeech callback
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start listening in mute chain", e)
                    mainHandler.post {
                        transitionState(RecognizerState.IDLE)
                        _errorMessage.value = "Failed to start: ${e.message}"
                        beepSuppressor.unmute()
                    }
                }
            }
            
            // Return immediately - the actual startListening happens 50ms later in Handler callback
            return true
            
        } else {
            Log.d(TAG, "Manual start: Allowing beep to play for user feedback")
            
            // No muting or delay - beep plays normally, faster UX
            withContext(Dispatchers.Main) {
                try {
                    speechRecognizer?.startListening(intent)
                    Log.d(TAG, "Started listening with locale: ${_selectedLocale.value}, isRestart=false")
                    // Note: silenceTimer will be started in onReadyForSpeech callback
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start listening", e)
                    transitionState(RecognizerState.IDLE)
                    _errorMessage.value = "Failed to start: ${e.message}"
                    return@withContext
                }
            }
            return true
        }
    }
    
    /**
     * Stop listening for speech.
     * Sets a flag to prevent auto-restart.
     */
    suspend fun stopListening() {
        Log.d(TAG, "stopListening() called, current state: $recognizerState")
        
        stopRequested = true
        isRestarting = false
        
        cancelScheduledRestart()
        cancelSilenceTimer()
        
        synchronized(stateLock) {
            if (!recognizerState.canStop() && recognizerState != RecognizerState.IDLE) {
                Log.w(TAG, "Cannot stop from state: $recognizerState")
                return
            }
            if (recognizerState == RecognizerState.IDLE) {
                updateListeningState(false)
                return
            }
            transitionState(RecognizerState.STOPPING)
        }
        
        withContext(Dispatchers.Main) {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recognizer", e)
            }
            transitionState(RecognizerState.IDLE)
            updateListeningState(false)
            _soundLevel.value = 0f
            _currentText.value = ""  // Clear recognized text on explicit stop
            
            // Reset segment count on explicit stop
            segmentCount = 0
        }
    }
    
    /**
     * Pause listening (can be resumed).
     */
    suspend fun pauseListening() {
        Log.d(TAG, "pauseListening() called")
        _isPaused.value = true
        stopListening()
    }
    
    /**
     * Resume listening from paused state.
     */
    suspend fun resumeListening() {
        Log.d(TAG, "resumeListening() called")
        if (_isPaused.value) {
            _isPaused.value = false
            startListening()
        }
    }
    
    /**
     * Clean up resources.
     */
    fun destroy() {
        Log.d(TAG, "Destroying speech recognition manager")
        
        serviceScope.cancel()
        cancelSilenceTimer()
        restartJob?.cancel()
        
        // Clean up beep suppressor
        beepSuppressor.cleanup()
        
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying recognizer", e)
            }
        }
        
        _isInitialized.value = false
    }
    
    // ==================== Configuration Methods ====================
    
    /**
     * Set the locale for speech recognition.
     */
    fun setLocale(localeId: String) {
        Log.d(TAG, "Setting locale: $localeId")
        _selectedLocale.value = localeId
        
        // If currently listening, use forceFullRestart to properly recreate recognizer with new locale
        if (_isListening.value) {
            serviceScope.launch {
                isRestarting = true
                stopRequested = false
                forceFullRestart()
            }
        }
    }
    
    /**
     * Clear current error message.
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    /**
     * Clear the current recognized text from the UI.
     * Used when resetting the app state (e.g., "Clear All Data").
     */
    fun clearCurrentText() {
        _currentText.value = ""
    }
    
    // ==================== Private Methods ====================
    
    private fun transitionState(newState: RecognizerState) {
        val oldState = recognizerState
        recognizerState = newState
        _recognizerStateFlow.value = newState
        lastStateChange = System.currentTimeMillis()
        Log.d(TAG, "State transition: $oldState -> $newState")
    }
    
    private fun updateListeningState(listening: Boolean) {
        if (_isListening.value != listening) {
            _isListening.value = listening
            Log.d(TAG, "Listening state updated: $listening")
        }
    }
    
    private fun createRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, _selectedLocale.value)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            
            // Enable segmented session for true continuous recognition (API 33+)
            // Recognition continues until explicitly stopped - no auto-termination on silence
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true)
            
            // Configure silence detection for segment boundaries
            // These control when a "segment" ends, not when recognition stops
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, pauseFor.inWholeMilliseconds)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, pauseFor.inWholeMilliseconds)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
        }
    }
    
    private fun createRecognitionListener(): RecognitionListener {
        return SegmentedSpeechListener()
    }
    
    private fun handleResult(results: Bundle?, isFinal: Boolean) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (matches.isNullOrEmpty()) return
        
        val text = matches[0]
        
        if (isFinal) {
            processFinalResult(text)
        } else {
            _currentText.value = text
            serviceScope.launch {
                _partialResults.emit(text)
            }
        }
    }
    
    private fun processFinalResult(text: String) {
        Log.d(TAG, "Final result: $text")
        
        // Reset error counter on successful recognition
        consecutiveErrors = 0
        
        // Parse text for commands
        val processed = CommandParser.processText(text)
        
        if (processed.hasCommand) {
            Log.d(TAG, "Command detected: ${processed.command}")
            serviceScope.launch {
                processed.command?.let { _recognizedCommand.emit(it) }
            }
        }
        
        // Send any text content
        if (processed.hasText) {
            val textContent = processed.combinedText ?: ""
            _currentText.value = textContent
            serviceScope.launch {
                _recognizedText.emit(textContent)
            }
        }
        // Note: We intentionally do NOT clear _currentText when there's no text.
        // This prevents UI flickering when speech recognition auto-restarts.
        // The currentText will be cleared when a new session starts or when
        // the user explicitly stops listening.
    }
    
    /**
     * Schedule a restart after error or unexpected session end.
     * With segmented sessions, restarts are only needed for error recovery,
     * not for continuous listening (which is handled by the session itself).
     */
    private fun scheduleRestart(delay: Duration = Duration.ZERO) {
        if (_isPaused.value || !autoRestart || stopRequested) {
            Log.d(TAG, "Restart skipped: paused=${_isPaused.value}, autoRestart=$autoRestart, stopRequested=$stopRequested")
            isRestarting = false
            updateListeningState(false)
            _soundLevel.value = 0f
            return
        }
        
        if (restartScheduled) {
            Log.d(TAG, "Restart already scheduled")
            return
        }
        
        restartScheduled = true
        
        restartJob?.cancel()
        restartJob = serviceScope.launch {
            if (delay > Duration.ZERO) {
                Log.d(TAG, "Waiting $delay before restart")
                delay(delay)
            }
            
            restartScheduled = false
            
            if (!_isPaused.value && autoRestart && !stopRequested) {
                Log.d(TAG, "Restarting segmented session")
                transitionState(RecognizerState.IDLE)
                startListening(isRestart = true)
            } else {
                Log.d(TAG, "Restart cancelled")
                isRestarting = false
                updateListeningState(false)
                _soundLevel.value = 0f
            }
        }
    }
    
    private fun cancelScheduledRestart() {
        restartJob?.cancel()
        restartJob = null
        restartScheduled = false
        isRestarting = false
    }
    
    /**
     * Restart sequence for transient errors (e.g., ERROR_NO_MATCH).
     * Follows the "Golden Path" timeline:
     * - T+0ms:   Mute immediately
     * - T+50ms:  Cancel & Destroy (Handler.postDelayed)
     * - T+100ms: Create & Start (Handler.postDelayed, 50ms after destroy)
     * - T+350ms: Unmute (Handler.postDelayed, 250ms after start)
     * 
     * This ensures:
     * 1. The audio HAL has applied the mute before cancel/destroy beeps can fire
     * 2. The OS has released the microphone lock from the destroyed instance
     * 3. The "Start Listening" beep plays into silence before unmuting
     */
    private fun restartWithMutedCancel() {
        // Step 1: Mute immediately (T+0ms)
         beepSuppressor.muteStreams()
        
        // Step 2: Wait for mute propagation, then cancel & destroy (T+50ms)
        mainHandler.postDelayed({
            performMutedCancelAndDestroy()
        }, SilentRestartTiming.mutePropagationDelayMs)
    }


    private fun performMutedCancelAndDestroy() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
            transitionState(RecognizerState.IDLE)
            Log.d(TAG, "Canceled and destroyed recognizer after muted window")
            
            // Step 3: Wait for hardware release, then create & start (T+100ms)
            mainHandler.postDelayed({
                performRecreateAndStart()
            }, SilentRestartTiming.hardwareReleaseDelayMs)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during cancel/destroy in muted restart", e)
            handleMutedRestartFailure("Failed to restart: ${e.message}")
        }
    }

    private fun performRecreateAndStart() {
        if (!stopRequested && !_isPaused.value && autoRestart) {
            try {
                // Create new recognizer instance
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createRecognitionListener())
                }
                
                transitionState(RecognizerState.STARTING)
                val intent = createRecognizerIntent()
                speechRecognizer?.startListening(intent)
                Log.d(TAG, "Started listening with new recognizer after muted cancel restart")
                
                // Step 4: Wait for beep suppression window, then unmute (T+350ms)
                mainHandler.postDelayed({
                    beepSuppressor.unmute()
                    isMutedForProcessing = false  // Reset after successful restart
                    Log.d(TAG, "Unmuted after beep suppression window (${SilentRestartTiming.beepSuppressionDelayMs}ms)")
                }, SilentRestartTiming.beepSuppressionDelayMs)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create/start recognizer in muted cancel restart", e)
                handleMutedRestartFailure("Failed to start: ${e.message}")
            }
        } else {
            Log.d(TAG, "Muted cancel restart: conditions changed, aborting")
            handleMutedRestartFailure(null)
        }
    }

    private fun handleMutedRestartFailure(errorMessage: String?) {
        beepSuppressor.unmute()
        isMutedForProcessing = false
        if (errorMessage != null) {
            transitionState(RecognizerState.IDLE)
            _errorMessage.value = errorMessage
        }
        isRestarting = false
        updateListeningState(false)
        _soundLevel.value = 0f
    }

    private fun performSilenceTimeoutRestart() {
        mainHandler.post {
            try {
                // Step 1: Mute before cancel to suppress any beep (T+0ms)
                beepSuppressor.muteStreams()
                
                // Step 2: Wait for mute propagation, then cancel & destroy (T+50ms)
                mainHandler.postDelayed({
                    performSilenceTimeoutCancelAndRecreate()
                }, SilentRestartTiming.mutePropagationDelayMs)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during silence timer recovery", e)
                _errorMessage.value = "Failed to recover: ${e.message}"
                beepSuppressor.unmute()
                transitionState(RecognizerState.IDLE)
                updateListeningState(false)
            }
        }
    }

    private fun performSilenceTimeoutCancelAndRecreate() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
            Log.d(TAG, "Canceled and destroyed recognizer during silence timeout")
            
            // Step 3: Wait for hardware release, then create & restart (T+100ms)
            mainHandler.postDelayed({
                performSilenceTimeoutRecreate()
            }, SilentRestartTiming.hardwareReleaseDelayMs)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during cancel/destroy in silence timer", e)
            beepSuppressor.unmute()
            transitionState(RecognizerState.IDLE)
            updateListeningState(false)
        }
    }

    private fun performSilenceTimeoutRecreate() {
        try {
            // Recreate recognizer
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createRecognitionListener())
            }
            
            Log.d(TAG, "Recognizer recreated after silence timeout")
            
            // Restart listening if not stopped
            if (!stopRequested && !_isPaused.value) {
                transitionState(RecognizerState.IDLE)
                
                // Start listening (will trigger beep)
                serviceScope.launch {
                    startListening(isRestart = true)
                }
                
                // Note: startListening(isRestart=true) uses muteAndExecute which handles its own unmute
                // So we don't manually unmute here
            } else {
                beepSuppressor.unmute()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recreating recognizer after silence timeout", e)
            _errorMessage.value = "Failed to restart: ${e.message}"
            beepSuppressor.unmute()
            transitionState(RecognizerState.IDLE)
            updateListeningState(false)
        }
    }
    
    /**
     * Determine whether a hard restart is needed for memory management.
     * With segmented sessions, this is based on:
     * - Segment count threshold (MAX_SEGMENTS_BEFORE_RESTART)
     * - Time since last full restart (FULL_RESTART_TIME_THRESHOLD)
     */
    private fun shouldPerformHardRestart(): Boolean {
        // Segment count threshold
        if (segmentCount >= MAX_SEGMENTS_BEFORE_RESTART) {
            Log.d(TAG, "Hard restart needed: segment limit reached ($segmentCount >= $MAX_SEGMENTS_BEFORE_RESTART)")
            return true
        }
        
        // Time-based threshold for multi-hour sessions
        val timeSinceLastFullRestart = System.currentTimeMillis() - lastFullRestartTime
        if (timeSinceLastFullRestart > FULL_RESTART_TIME_THRESHOLD.inWholeMilliseconds) {
            Log.d(TAG, "Hard restart needed: time threshold exceeded (${timeSinceLastFullRestart}ms)")
            return true
        }
        
        return false
    }
    
    /**
     * Schedule a hard restart for memory management.
     * This stops the current session and recreates the recognizer.
     */
    private fun scheduleHardRestart() {
        if (_isPaused.value || stopRequested) {
            return
        }
        
        restartJob?.cancel()
        restartJob = serviceScope.launch {
            isRestarting = true
            forceFullRestart()
        }
    }
    
    /**
     * Start the proactive silence timer (beep prevention).
     * This timer expires after SILENCE_TIMEOUT if not reset by speech activity.
     * On expiry, it preemptively cancels the recognizer to avoid Google's error beep,
     * then recreates and restarts the session.
     */
    private fun startSilenceTimer() {
        silenceTimerJob?.cancel()
        
        silenceTimerJob = serviceScope.launch {
            try {
                delay(SILENCE_TIMEOUT)
                
                // Timer expired - check if we need to take action
                val now = System.currentTimeMillis()
                val timeSinceStateChange = now - lastStateChange
                
                // Check for stuck states first
                if (recognizerState == RecognizerState.STARTING && 
                    timeSinceStateChange > STUCK_STATE_TIMEOUT.inWholeMilliseconds) {
                    Log.w(TAG, "Silence timer: Stuck in STARTING state, forcing restart")
                    forceFullRestart()
                    return@launch
                }
                
                if (recognizerState == RecognizerState.STOPPING && 
                    timeSinceStateChange > STUCK_STATE_TIMEOUT.inWholeMilliseconds) {
                    Log.w(TAG, "Silence timer: Stuck in STOPPING state, forcing restart")
                    forceFullRestart()
                    return@launch
                }
                
                // Normal silence timeout - preemptively cancel with mute to prevent beep
                if (recognizerState == RecognizerState.LISTENING && !stopRequested) {
                    Log.d(TAG, "Silence timer expired after ${SILENCE_TIMEOUT.inWholeSeconds}s - preemptive muted cancel")
                    performSilenceTimeoutRestart()
                }
            } catch (e: CancellationException) {
                // Timer was reset - this is normal
                Log.v(TAG, "Silence timer cancelled (reset)")
            }
        }
    }
    
    /**
     * Reset the silence timer (restart the countdown).
     * Called when speech activity is detected.
     */
    private fun resetSilenceTimer() {
        if (recognizerState == RecognizerState.LISTENING) {
            silenceTimerJob?.cancel()
            startSilenceTimer()
            Log.v(TAG, "Silence timer reset")
        }
    }
    
    /**
     * Cancel the silence timer (stop monitoring).
     * Called when stopping or pausing.
     */
    private fun cancelSilenceTimer() {
        silenceTimerJob?.cancel()
        silenceTimerJob = null
        Log.v(TAG, "Silence timer cancelled")
    }
    
    /**
     * Cancel the early mute safety timeout.
     * Called when a terminal callback (onResults/onError/onSegmentResults) fires.
     */
    private fun cancelEarlyMuteTimeout() {
        earlyMuteTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        earlyMuteTimeoutRunnable = null
    }
    
    /**
     * Force a full restart by destroying and recreating the recognizer.
     * This clears any accumulated memory and resets all counters.
     * Uses Handler.postDelayed for proper message queue ordering.
     */
    private suspend fun forceFullRestart() {
        Log.d(TAG, "Force full restart initiated (segment count was: $segmentCount)")
        
        if (stopRequested) {
            Log.d(TAG, "Force restart skipped - stop was requested")
            isRestarting = false
            updateListeningState(false)
            _soundLevel.value = 0f
            return
        }
        
        val wasListening = _isListening.value
        
        withContext(Dispatchers.Main) {
            try {
                // Cancel and destroy current recognizer
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
                
                // Safety delay for OS cleanup using Handler.postDelayed
                mainHandler.postDelayed({
                    try {
                        // Create new recognizer
                        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                            setRecognitionListener(createRecognitionListener())
                        }
                        
                        // Reset counters for the new recognizer instance
                        segmentCount = 0
                        lastFullRestartTime = System.currentTimeMillis()
                        consecutiveErrors = 0
                        Log.d(TAG, "Counters reset for new recognizer instance")
                        
                        transitionState(RecognizerState.IDLE)
                        
                        if (!_isPaused.value && autoRestart && !stopRequested) {
                            serviceScope.launch {
                                startListening(isRestart = wasListening)
                            }
                        } else {
                            isRestarting = false
                            updateListeningState(false)
                            _soundLevel.value = 0f
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating new recognizer in force restart", e)
                        _errorMessage.value = "Failed to restart: ${e.message}"
                        isRestarting = false
                        updateListeningState(false)
                    }
                }, SilentRestartTiming.hardwareReleaseDelayMs)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during force restart", e)
                _errorMessage.value = "Failed to restart: ${e.message}"
                isRestarting = false
                updateListeningState(false)
            }
        }
    }
    
    private fun loadAvailableLocales() {
        // Get system locales as available options
        val locales = mutableListOf<Locale>()
        
        // Add common locales
        locales.add(Locale.US)
        locales.add(Locale.UK)
        locales.add(Locale.CANADA)
        locales.add(Locale.GERMANY)
        locales.add(Locale.FRANCE)
        locales.add(Locale.ITALY)
        locales.add(Locale.JAPAN)
        locales.add(Locale.KOREA)
        locales.add(Locale.CHINA)
        locales.add(Locale("es", "ES"))
        locales.add(Locale("pt", "BR"))
        locales.add(Locale("ru", "RU"))
        locales.add(Locale("cs", "CZ"))
        
        _availableLocales.value = locales.distinctBy { it.toLanguageTag() }
    }
    
    // ==================== Recognition Listener ====================
    
    /**
     * Speech listener with segmented session support.
     * 
     * With EXTRA_SEGMENTED_SESSION enabled:
     * - onSegmentResults() is called for each segment (replaces onResults() for segments)
     * - onEndOfSegmentedSession() is called when recognition ends (stop requested or error)
     * - Recognition continues automatically between segments (no restart needed)
     * - onResults() may still be called in some cases (device-dependent)
     */
    private inner class SegmentedSpeechListener : RecognitionListener {
        
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
            transitionState(RecognizerState.LISTENING)
            updateListeningState(true)
            isRestarting = false
            isMutedForProcessing = false  // Ensure clean state for new session
            _errorMessage.value = null
            
            // Reset silence timer - recognizer is ready
            resetSilenceTimer()
        }
        
        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
            // Note: Don't reset silence timer here - this callback fires immediately
            // before ERROR_NO_MATCH in many cases, which would defeat the timer.
            // Only onPartialResults (actual transcription) should reset the timer.
        }
        
        override fun onRmsChanged(rmsdB: Float) {
            // Convert dB to 0-1 range (rmsdB typically ranges from -2 to 10)
            val normalized = ((rmsdB + 2) / 12).coerceIn(0f, 1f)
            _soundLevel.value = normalized
        }
        
        override fun onBufferReceived(buffer: ByteArray?) {
            // Not typically used
        }
        
        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech (segment boundary)")
            
            // Early mute to catch the success beep that fires between 
            // onEndOfSpeech and onResults (legacy callback path).
            // This preemptively mutes audio BEFORE the OS starts playing the beep.
            if (!isMutedForProcessing && !beepSuppressor.isMutedState) {
                Log.d(TAG, "onEndOfSpeech: early mute to suppress success beep")
                beepSuppressor.muteStreams()
                isMutedForProcessing = true
                
                // Safety net: unmute after 500ms if no terminal callback clears this.
                // This handles edge cases where onEndOfSpeech fires but no terminal 
                // callback (onResults/onError/onSegmentResults) follows.
                val timeoutRunnable = Runnable {
                    if (isMutedForProcessing) {
                        Log.w(TAG, "onEndOfSpeech safety unmute: no terminal callback within 500ms")
                        beepSuppressor.unmute()
                        isMutedForProcessing = false
                    }
                }
                earlyMuteTimeoutRunnable = timeoutRunnable
                mainHandler.postDelayed(timeoutRunnable, 500L)
            }
        }
        
        override fun onError(error: Int) {
            Log.w(TAG, "onError: $error")
            
            // Cancel safety timeout and silence timer from current session
            cancelEarlyMuteTimeout()
            cancelSilenceTimer()
            
            // If onEndOfSpeech didn't fire (e.g., ERROR_NO_MATCH timeout), 
            // we may not be muted yet. Mute now if needed.
            if (!isMutedForProcessing && !beepSuppressor.isMutedState) {
                Log.d(TAG, "onError: onEndOfSpeech didn't fire, muting now")
                beepSuppressor.muteStreams()
                isMutedForProcessing = true
            }
            
            val classification = errorHandler.classify(error)
            
            if (classification.isTransient && !stopRequested && autoRestart) {
                // Transient error — use dedicated muted cancel restart sequence
                Log.d(TAG, "Transient error in segmented session, muted cancel restart")
                isRestarting = true
                restartWithMutedCancel()
            } else if (!classification.isTransient) {
                // Real error — use Golden Path: mute, cancel, unmute with proper delays
                Log.d(TAG, "Non-transient error: ${classification.message}")
                
                beepSuppressor.muteStreams()
                
                mainHandler.postDelayed({
                    speechRecognizer?.cancel()
                    transitionState(RecognizerState.IDLE)
                    
                    // Unmute after beep suppression window
                    mainHandler.postDelayed({
                        beepSuppressor.unmute()
                    }, SilentRestartTiming.beepSuppressionDelayMs)
                    
                }, SilentRestartTiming.mutePropagationDelayMs)
                
                consecutiveErrors++
                _errorMessage.value = classification.message
                
                if (consecutiveErrors < MAX_CONSECUTIVE_ERRORS && autoRestart && !stopRequested) {
                    val backoffDelay = errorHandler.calculateBackoff(consecutiveErrors)
                    Log.d(TAG, "Error with backoff restart: $backoffDelay")
                    isRestarting = true
                    scheduleRestart(delay = backoffDelay)
                } else {
                    Log.e(TAG, "Max consecutive errors or stop requested, stopping")
                    updateListeningState(false)
                    _soundLevel.value = 0f
                }
            } else {
                // Stop was requested — mute, cancel, unmute with delays
                Log.d(TAG, "Error during stop request")
                
                beepSuppressor.muteStreams()
                
                mainHandler.postDelayed({
                    speechRecognizer?.cancel()
                    transitionState(RecognizerState.IDLE)
                    
                    mainHandler.postDelayed({
                        beepSuppressor.unmute()
                    }, SilentRestartTiming.beepSuppressionDelayMs)
                    
                }, SilentRestartTiming.mutePropagationDelayMs)
                
                updateListeningState(false)
                _soundLevel.value = 0f
            }
        }
        
        /**
         * Called when a segment completes in segmented session mode.
         * Recognition continues automatically after this.
         */
        override fun onSegmentResults(results: Bundle) {
            Log.d(TAG, "onSegmentResults (segment #$segmentCount)")
            
            // Cancel early mute safety timeout
            cancelEarlyMuteTimeout()
            
            // If early mute was applied at onEndOfSpeech, unmute now.
            // Segmented sessions continue automatically — no restart beep to suppress.
            if (isMutedForProcessing) {
                Log.d(TAG, "onSegmentResults: unmuting early mute (no restart needed for segments)")
                beepSuppressor.unmute()
                isMutedForProcessing = false
            }
            
            // Reset silence timer on successful segment
            resetSilenceTimer()
            
            handleResult(results, isFinal = true)
            
            // Increment segment counter for memory management
            segmentCount++
            consecutiveErrors = 0  // Reset error counter on successful segment
            
            // Check if we need a hard restart for memory management
            if (shouldPerformHardRestart()) {
                Log.d(TAG, "Scheduling hard restart for memory management (segments: $segmentCount)")
                scheduleHardRestart()
            }
            
            // Note: Recognition continues automatically with segmented sessions
            // No restart needed here
        }
        
        /**
         * Called when the segmented session ends.
         * This happens when stopListening() is called or on certain errors.
         */
        override fun onEndOfSegmentedSession() {
            Log.d(TAG, "onEndOfSegmentedSession (total segments: $segmentCount)")
            transitionState(RecognizerState.IDLE)
            
            if (!stopRequested && autoRestart && !isRestarting) {
                // Session ended unexpectedly, restart
                Log.d(TAG, "Segmented session ended unexpectedly, will restart")
                isRestarting = true
                scheduleRestart(delay = 100.milliseconds)
            } else {
                updateListeningState(false)
                _soundLevel.value = 0f
            }
        }
        
        /**
         * Legacy callback - may still be called on some devices.
         * Treat as segment result for compatibility.
         */
         override fun onResults(results: Bundle?) {
            Log.d(TAG, "onResults (legacy callback)")
            cancelEarlyMuteTimeout()
            results?.let { handleResult(it, isFinal = true) }
            
            // In segmented mode, this shouldn't normally be called
            // but handle it gracefully if it is
            if (!stopRequested && autoRestart) {
                transitionState(RecognizerState.IDLE)
                isRestarting = true
                
                if (isMutedForProcessing) {
                    // Already muted from onEndOfSpeech — go directly to 
                    // Golden Path restart, skipping the redundant mute step.
                    // restartWithMutedCancel() will detect already-muted state 
                    // via BeepSuppressor.isMuted guard and proceed to cancel/destroy.
                    Log.d(TAG, "onResults: already muted from onEndOfSpeech, direct restart")
                    restartWithMutedCancel()
                } else {
                    // onEndOfSpeech didn't fire or didn't mute — normal path
                    Log.d(TAG, "onResults: onEndOfSpeech didn't mute, scheduling restart normally")
                    scheduleRestart(delay = Duration.ZERO)
                }
            } else {
                // Not restarting — clean up early mute state
                if (isMutedForProcessing) {
                    Log.d(TAG, "onResults: not restarting, unmuting early mute")
                    beepSuppressor.unmute()
                    isMutedForProcessing = false
                }
            }
        }
        
        override fun onPartialResults(partialResults: Bundle?) {
            // User is actively speaking - reset silence timer continuously
            resetSilenceTimer()
            handleResult(partialResults, isFinal = false)
        }
        
        override fun onEvent(eventType: Int, params: Bundle?) {
            Log.d(TAG, "onEvent: $eventType")
        }
    }
}
