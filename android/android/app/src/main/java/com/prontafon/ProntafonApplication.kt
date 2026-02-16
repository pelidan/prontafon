package com.prontafon

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.prontafon.service.AudioFocusObserver
import com.prontafon.service.speech.SpeechRecognitionManager
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Main Application class for Prontafon.
 * 
 * Annotated with @HiltAndroidApp to enable Hilt dependency injection
 * throughout the application. This serves as the root of the dependency
 * graph and must be declared in AndroidManifest.xml.
 * 
 * Handles:
 * - Application lifecycle management
 * - Speech recognition lifecycle (stops when app goes to background)
 * - Audio focus monitoring (stops when calls or VoIP apps are active)
 */
@HiltAndroidApp
class ProntafonApplication : Application() {

    companion object {
        private const val TAG = "ProntafonApp"
    }

    private val applicationScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        
        Log.d(TAG, "Application onCreate - initializing Prontafon")
        
        // Initialize any app-wide configurations here
        initializeLogging()
        
        try {
            // Get dependencies via entry point
            val entryPoint = EntryPointAccessors.fromApplication(
                this,
                AppDependencies::class.java
            )
            
            val speechRecognitionManager = entryPoint.speechRecognitionManager()
            val audioFocusObserver = entryPoint.audioFocusObserver()
            
            // Initialize speech recognition manager on app start
            applicationScope.launch {
                speechRecognitionManager.initialize()
                Log.d(TAG, "Speech recognition manager initialized")
            }
            
            // Register lifecycle observer to stop speech recognition when app goes to background
            val lifecycleObserver = object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    Log.d(TAG, "App moved to background - stopping speech recognition")
                    applicationScope.launch {
                        speechRecognitionManager.stopListening()
                    }
                }
            }
            ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
            
            // Register audio focus observer to stop speech recognition during calls and VoIP
            audioFocusObserver.register()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize lifecycle observers", e)
        }
    }

    private fun initializeLogging() {
        // Configure logging based on build type
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Debug build - verbose logging enabled")
        }
    }

    /**
     * Entry point for accessing dependencies at runtime
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppDependencies {
        fun speechRecognitionManager(): SpeechRecognitionManager
        fun audioFocusObserver(): AudioFocusObserver
    }
}
