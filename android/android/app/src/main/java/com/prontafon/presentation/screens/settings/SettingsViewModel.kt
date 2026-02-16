package com.prontafon.presentation.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.prontafon.data.model.PairedDevice
import com.prontafon.data.repository.PreferencesRepository
import com.prontafon.service.ble.BleManager
import com.prontafon.service.speech.SpeechRecognitionManager
import com.prontafon.util.crypto.SecureStorageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 * Manages app preferences and configuration.
 * 
 * Features:
 * - Speech recognition settings
 * - Connection settings
 * - Paired devices management
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val speechRecognitionManager: SpeechRecognitionManager,
    private val secureStorage: SecureStorageManager,
    private val bleManager: BleManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    // ==================== Speech Settings ====================
    
    val selectedLocale: StateFlow<String> = preferencesRepository.selectedLocale
    val availableLocales: StateFlow<List<Locale>> = speechRecognitionManager.availableLocales
    val showRecognizedText: StateFlow<Boolean> = preferencesRepository.showRecognizedText
    val keepScreenOn: StateFlow<Boolean> = preferencesRepository.keepScreenOn
    
    // ==================== Connection Settings ====================
    
    val autoReconnect: StateFlow<Boolean> = preferencesRepository.autoReconnect
    
    // ==================== Paired Devices ====================
    
    private val _pairedDevices = MutableStateFlow<List<PairedDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedDevice>> = _pairedDevices.asStateFlow()
    
    // ==================== Navigation Events ====================
    
    private val _navigationEvent = MutableSharedFlow<NavigationEvent>(extraBufferCapacity = 1)
    val navigationEvent: SharedFlow<NavigationEvent> = _navigationEvent.asSharedFlow()
    
    // ==================== UI Events ====================
    
    private val _uiEvent = Channel<SettingsUiEvent>(Channel.BUFFERED)
    val uiEvent: Flow<SettingsUiEvent> = _uiEvent.receiveAsFlow()
    
    // ==================== Initialization ====================
    
    init {
        loadPairedDevices()
    }
    
    // ==================== Speech Settings Actions ====================
    
    fun setLocale(locale: String) {
        preferencesRepository.setSelectedLocale(locale)
        speechRecognitionManager.setLocale(locale)
    }
    
    fun setShowRecognizedText(enabled: Boolean) {
        preferencesRepository.setShowRecognizedText(enabled)
    }
    
    fun setKeepScreenOn(enabled: Boolean) {
        preferencesRepository.setKeepScreenOn(enabled)
    }
    
    // ==================== Connection Actions ====================
    
    fun setAutoReconnect(enabled: Boolean) {
        preferencesRepository.setAutoReconnect(enabled)
    }
    
    // ==================== Paired Devices Actions ====================
    
    fun forgetPairedDevice(address: String) {
        viewModelScope.launch {
            secureStorage.removePairedDevice(address)
            loadPairedDevices()
        }
    }
    
    /**
     * Clears all app data and resets to defaults.
     * This includes:
     * - All paired devices and shared secrets (from secure storage)
     * - All preferences (reset to defaults)
     * - BLE connection state (disconnect)
     * - Speech recognition state (via preferences reset)
     */
    fun clearAllData() {
        viewModelScope.launch {
            // 1. Clear all secure storage (paired devices, shared secrets, etc.)
            secureStorage.clearAll()
            
            // 2. Reset all preferences to defaults
            preferencesRepository.resetToDefaults()
            
            // 3. Disconnect from current BLE device
            bleManager.disconnect()
            
            // 4. Reset speech recognition locale to default
            speechRecognitionManager.setLocale(preferencesRepository.selectedLocale.value)
            
            // Clear the recognized text from UI
            speechRecognitionManager.clearCurrentText()
            
            // 5. Reload paired devices (should be empty now)
            loadPairedDevices()
            
            // 6. Signal navigation to home screen
            _navigationEvent.emit(NavigationEvent.NavigateToHome)
        }
    }
    
    // ==================== Private Methods ====================
    
    private fun loadPairedDevices() {
        viewModelScope.launch {
            // Get all paired devices from secure storage
            val result = secureStorage.getPairedDevices()
            result.onSuccess { devices ->
                _pairedDevices.value = devices
            }.onFailure {
                _pairedDevices.value = emptyList()
            }
        }
    }
}

/**
 * Navigation events emitted by SettingsViewModel.
 */
sealed class NavigationEvent {
    object NavigateToHome : NavigationEvent()
}

/**
 * One-time UI events for Settings screen
 */
sealed interface SettingsUiEvent {
    data class ShowMessage(val message: String) : SettingsUiEvent
}
