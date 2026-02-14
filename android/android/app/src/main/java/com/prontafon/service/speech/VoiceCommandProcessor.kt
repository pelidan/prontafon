package com.prontafon.service.speech

import android.util.Log
import com.prontafon.domain.model.CommandCode
import com.prontafon.domain.model.CommandParser
import com.prontafon.domain.model.Message
import com.prontafon.domain.model.ProcessedSpeech
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Processes recognized speech text and commands.
 * 
 * Responsibilities:
 * - Parse recognized text for commands using CommandParser
 * - Extract text before/after commands
 * - Handle combined text + command scenarios
 * - Create Message objects for BLE transmission
 * - Emit processed results via SharedFlow
 */
@Singleton
class VoiceCommandProcessor @Inject constructor() {
    
    companion object {
        private const val TAG = "VoiceCommandProcessor"
    }
    
    // Shared flows for processed output
    private val _textMessages = MutableSharedFlow<Message>(extraBufferCapacity = 10)
    val textMessages: SharedFlow<Message> = _textMessages.asSharedFlow()
    
    private val _commandMessages = MutableSharedFlow<Message>(extraBufferCapacity = 10)
    val commandMessages: SharedFlow<Message> = _commandMessages.asSharedFlow()
    
    private val _processedSpeech = MutableSharedFlow<ProcessedSpeech>(extraBufferCapacity = 10)
    val processedSpeech: SharedFlow<ProcessedSpeech> = _processedSpeech.asSharedFlow()
    
    /**
     * Process recognized speech text.
     * Parses for commands and emits appropriate messages.
     */
    suspend fun processRecognizedText(text: String) {
        if (text.isBlank()) {
            Log.d(TAG, "Ignoring blank text")
            return
        }
        
        Log.d(TAG, "Processing recognized text: $text")
        
        // Parse text for commands
        val processed = CommandParser.processText(text)
        
        // Emit processed result
        _processedSpeech.emit(processed)
        
        // Handle text before command
        if (!processed.textBefore.isNullOrBlank()) {
            val textMessage = Message.text(processed.textBefore)
            Log.d(TAG, "Emitting text before command: ${processed.textBefore}")
            _textMessages.emit(textMessage)
        }
        
        // Handle command
        if (processed.hasCommand) {
            val commandMessage = Message.command(processed.command!!.code)
            Log.d(TAG, "Emitting command: ${processed.command}")
            _commandMessages.emit(commandMessage)
        }
        
        // Handle text after command
        if (!processed.textAfter.isNullOrBlank()) {
            val textMessage = Message.text(processed.textAfter)
            Log.d(TAG, "Emitting text after command: ${processed.textAfter}")
            _textMessages.emit(textMessage)
        }
        
        // If no command found, emit as plain text
        if (!processed.hasCommand && processed.hasText) {
            val textMessage = Message.text(text.trim())
            Log.d(TAG, "Emitting plain text: $text")
            _textMessages.emit(textMessage)
        }
    }
    
    /**
     * Process a command directly (from command detector).
     */
    suspend fun processCommand(command: CommandCode) {
        Log.d(TAG, "Processing command: $command")
        
        val commandMessage = Message.command(command.code)
        _commandMessages.emit(commandMessage)
        
        // Emit as processed speech
        val processed = ProcessedSpeech.commandOnly(command)
        _processedSpeech.emit(processed)
    }
    
    /**
     * Check if text contains a command.
     */
    fun containsCommand(text: String): Boolean {
        return CommandParser.containsCommand(text)
    }
    
    /**
     * Parse text and return processed result without emitting.
     */
    fun parseText(text: String): ProcessedSpeech {
        return CommandParser.processText(text)
    }
}
