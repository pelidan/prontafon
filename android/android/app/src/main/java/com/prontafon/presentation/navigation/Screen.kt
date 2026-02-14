package com.prontafon.presentation.navigation

/**
 * Sealed class representing all navigation destinations in the app
 * Each screen has a route string used by Compose Navigation
 */
sealed class Screen(val route: String) {
    /**
     * Home screen - Main screen with speech recognition start/stop
     */
    object Home : Screen("home")
    
    /**
     * Connection screen - BLE device scanning and pairing
     */
    object Connection : Screen("connection")
    
    /**
     * Settings screen - App preferences and configuration
     */
    object Settings : Screen("settings")
}
