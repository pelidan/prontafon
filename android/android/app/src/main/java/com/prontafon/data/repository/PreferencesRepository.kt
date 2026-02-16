package com.prontafon.data.repository

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing app preferences using SharedPreferences.
 * Provides reactive access to settings via StateFlows.
 */
@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        private const val PREFS_NAME = "prontafon_prefs"
        
        // Keys
        private const val KEY_SELECTED_LOCALE = "selected_locale"
        private const val KEY_SHOW_RECOGNIZED_TEXT = "show_recognized_text"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        
        // Defaults
        private const val DEFAULT_LOCALE = "cs-CZ"
        private const val DEFAULT_SHOW_RECOGNIZED_TEXT = true
        private const val DEFAULT_KEEP_SCREEN_ON = true
        private const val DEFAULT_AUTO_RECONNECT = true
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // ==================== Speech Settings ====================
    
    private val _selectedLocale = MutableStateFlow(getString(KEY_SELECTED_LOCALE, DEFAULT_LOCALE))
    val selectedLocale: StateFlow<String> = _selectedLocale.asStateFlow()
    
    private val _showRecognizedText = MutableStateFlow(getBoolean(KEY_SHOW_RECOGNIZED_TEXT, DEFAULT_SHOW_RECOGNIZED_TEXT))
    val showRecognizedText: StateFlow<Boolean> = _showRecognizedText.asStateFlow()
    
    private val _keepScreenOn = MutableStateFlow(getBoolean(KEY_KEEP_SCREEN_ON, DEFAULT_KEEP_SCREEN_ON))
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()
    
    // ==================== Connection Settings ====================
    
    private val _autoReconnect = MutableStateFlow(getBoolean(KEY_AUTO_RECONNECT, DEFAULT_AUTO_RECONNECT))
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()
    
    // ==================== Setters ====================
    
    fun setSelectedLocale(locale: String) {
        putString(KEY_SELECTED_LOCALE, locale)
        _selectedLocale.value = locale
    }
    
    fun setShowRecognizedText(enabled: Boolean) {
        putBoolean(KEY_SHOW_RECOGNIZED_TEXT, enabled)
        _showRecognizedText.value = enabled
    }
    
    fun setKeepScreenOn(enabled: Boolean) {
        putBoolean(KEY_KEEP_SCREEN_ON, enabled)
        _keepScreenOn.value = enabled
    }
    
    fun setAutoReconnect(enabled: Boolean) {
        putBoolean(KEY_AUTO_RECONNECT, enabled)
        _autoReconnect.value = enabled
    }
    
    // ==================== Private Helpers ====================
    
    private fun getString(key: String, default: String): String {
        return prefs.getString(key, default) ?: default
    }
    
    private fun getBoolean(key: String, default: Boolean): Boolean {
        return prefs.getBoolean(key, default)
    }
    
    private fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    
    private fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
    
    // ==================== Reset ====================
    
    /**
     * Resets all preferences to their default values.
     * Clears SharedPreferences and resets all StateFlows.
     */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
        _selectedLocale.value = DEFAULT_LOCALE
        _showRecognizedText.value = DEFAULT_SHOW_RECOGNIZED_TEXT
        _keepScreenOn.value = DEFAULT_KEEP_SCREEN_ON
        _autoReconnect.value = DEFAULT_AUTO_RECONNECT
    }
}
