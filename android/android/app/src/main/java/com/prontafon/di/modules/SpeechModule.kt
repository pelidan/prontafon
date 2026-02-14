package com.prontafon.di.modules

import android.content.Context
import android.speech.SpeechRecognizer
import com.prontafon.service.speech.BeepSuppressor
import com.prontafon.service.speech.SpeechErrorHandler
import com.prontafon.service.speech.SpeechRecognitionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for speech recognition dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object SpeechModule {
    
    /**
     * Provide BeepSuppressor singleton.
     */
    @Provides
    @Singleton
    fun provideBeepSuppressor(
        @ApplicationContext context: Context
    ): BeepSuppressor {
        return BeepSuppressor(context)
    }
    
    /**
     * Provide SpeechErrorHandler singleton.
     */
    @Provides
    @Singleton
    fun provideSpeechErrorHandler(): SpeechErrorHandler {
        return SpeechErrorHandler()
    }
    
    /**
     * Provide SpeechRecognitionManager singleton.
     */
    @Provides
    @Singleton
    fun provideSpeechRecognitionManager(
        @ApplicationContext context: Context,
        errorHandler: SpeechErrorHandler,
        beepSuppressor: BeepSuppressor
    ): SpeechRecognitionManager {
        return SpeechRecognitionManager(context, errorHandler, beepSuppressor)
    }
}
