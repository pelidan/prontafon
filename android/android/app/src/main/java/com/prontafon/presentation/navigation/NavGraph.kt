package com.prontafon.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.prontafon.presentation.screens.connection.ConnectionScreen
import com.prontafon.presentation.screens.home.HomeScreen
import com.prontafon.presentation.screens.settings.SettingsScreen

/**
 * Main navigation graph for the Prontafon app
 * Defines all navigation routes and their associated screens
 */
@Composable
fun NavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Home.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        // Home Screen - Main screen with speech recognition
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToConnection = { navController.navigateToConnection() },
                onNavigateToSettings = { navController.navigateToSettings() },
                viewModel = hiltViewModel()
            )
        }
        
        // Connection Screen - BLE device scanning and pairing
        composable(Screen.Connection.route) {
            ConnectionScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = hiltViewModel()
            )
        }
        
        // Settings Screen - App preferences
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                viewModel = hiltViewModel()
            )
        }
    }
}

/**
 * Extension functions for NavController navigation
 */
fun NavHostController.navigateToHome() {
    navigate(Screen.Home.route) {
        popUpTo(Screen.Home.route) { inclusive = true }
    }
}

fun NavHostController.navigateToConnection() {
    navigate(Screen.Connection.route)
}

fun NavHostController.navigateToSettings() {
    navigate(Screen.Settings.route)
}
