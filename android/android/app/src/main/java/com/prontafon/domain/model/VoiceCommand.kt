package com.prontafon.domain.model

/**
 * UI representation of a voice command for display purposes
 */
data class VoiceCommand(
    val trigger: String,
    val description: String,
    val example: String = ""
)

/**
 * Command codes for voice-triggered actions.
 * These are sent to the Linux host for execution.
 */
enum class CommandCode(val code: String) {
    ENTER("ENTER"),
    SELECT_ALL("SELECT_ALL"),
    COPY("COPY"),
    PASTE("PASTE"),
    CUT("CUT"),
    CANCEL("CANCEL");

    companion object {
        fun fromCode(code: String): CommandCode? {
            return entries.find { it.code.equals(code, ignoreCase = true) }
        }
    }
}

/**
 * Result of processing speech text for commands
 */
data class ProcessedSpeech(
    val textBefore: String?,
    val command: CommandCode?,
    val textAfter: String?
) {
    /**
     * Whether there is any text content (before or after command)
     */
    val hasText: Boolean
        get() = !textBefore.isNullOrBlank() || !textAfter.isNullOrBlank()

    /**
     * Whether a command was detected
     */
    val hasCommand: Boolean
        get() = command != null

    /**
     * Whether there is text after the command
     */
    val hasRemainder: Boolean
        get() = !textAfter.isNullOrBlank()

    /**
     * Combined text (before + after), null if no text
     */
    val combinedText: String?
        get() {
            val before = textBefore?.trim() ?: ""
            val after = textAfter?.trim() ?: ""
            val combined = "$before $after".trim()
            return combined.ifEmpty { null }
        }

    companion object {
        /**
         * Create result with only text (no command)
         */
        fun textOnly(text: String): ProcessedSpeech {
            return ProcessedSpeech(
                textBefore = text.trim().ifEmpty { null },
                command = null,
                textAfter = null
            )
        }

        /**
         * Create result with only command (no text)
         */
        fun commandOnly(command: CommandCode): ProcessedSpeech {
            return ProcessedSpeech(
                textBefore = null,
                command = command,
                textAfter = null
            )
        }

        /**
         * Empty result
         */
        val EMPTY = ProcessedSpeech(null, null, null)
    }
}

/**
 * Parser for detecting voice commands in speech text.
 * Matches Flutter's CommandParser implementation.
 */
object CommandParser {
    /**
     * Command patterns mapped to command codes.
     * Patterns are matched case-insensitively.
     */
    private val patterns: Map<String, CommandCode> = mapOf(
        // Enter/newline variations
        "new line" to CommandCode.ENTER,
        "newline" to CommandCode.ENTER,
        "enter" to CommandCode.ENTER,
        "new paragraph" to CommandCode.ENTER,
        "next line" to CommandCode.ENTER,

        // Selection
        "select all" to CommandCode.SELECT_ALL,
        "select everything" to CommandCode.SELECT_ALL,

        // Copy variations
        "copy that" to CommandCode.COPY,
        "copy this" to CommandCode.COPY,
        "copy" to CommandCode.COPY,
        "copy text" to CommandCode.COPY,

        // Paste variations
        "paste" to CommandCode.PASTE,
        "paste that" to CommandCode.PASTE,
        "paste text" to CommandCode.PASTE,

        // Cut variations
        "cut that" to CommandCode.CUT,
        "cut this" to CommandCode.CUT,
        "cut" to CommandCode.CUT,
        "cut text" to CommandCode.CUT,

        // Cancel variations
        "cancel" to CommandCode.CANCEL,
        "cancel that" to CommandCode.CANCEL,
        "never mind" to CommandCode.CANCEL,
        "nevermind" to CommandCode.CANCEL
    )

    /**
     * Sorted patterns for matching (longest first to avoid partial matches)
     */
    private val sortedPatterns: List<Pair<String, CommandCode>> =
        patterns.entries
            .sortedByDescending { it.key.length }
            .map { it.key to it.value }

    /**
     * Parse text to find a command.
     * Returns the first matching command or null.
     */
    fun parse(text: String): CommandCode? {
        val normalized = text.lowercase().trim()
        
        for ((pattern, command) in sortedPatterns) {
            if (normalized == pattern) {
                return command
            }
        }
        return null
    }

    /**
     * Process text to extract command and surrounding text.
     * Handles cases like "hello new line world" -> text="hello", cmd=ENTER, after="world"
     */
    fun processText(text: String): ProcessedSpeech {
        if (text.isBlank()) {
            return ProcessedSpeech.EMPTY
        }

        val normalized = text.lowercase()

        // Try to find a command pattern in the text
        for ((pattern, command) in sortedPatterns) {
            val index = normalized.indexOf(pattern)
            if (index != -1) {
                // Found a command - extract text before and after
                val before = text.substring(0, index).trim()
                val after = text.substring(index + pattern.length).trim()

                return ProcessedSpeech(
                    textBefore = before.ifEmpty { null },
                    command = command,
                    textAfter = after.ifEmpty { null }
                )
            }
        }

        // No command found - return as plain text
        return ProcessedSpeech.textOnly(text)
    }

    /**
     * Check if text contains any command pattern
     */
    fun containsCommand(text: String): Boolean {
        val normalized = text.lowercase()
        return sortedPatterns.any { (pattern, _) ->
            normalized.contains(pattern)
        }
    }
}
