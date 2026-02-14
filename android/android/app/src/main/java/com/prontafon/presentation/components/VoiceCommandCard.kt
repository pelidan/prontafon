package com.prontafon.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prontafon.domain.model.VoiceCommand
import com.prontafon.presentation.theme.*

/**
 * Card component for displaying available voice commands
 * Shows command trigger, description, and status
 */
@Composable
fun VoiceCommandCard(
    command: VoiceCommand,
    modifier: Modifier = Modifier,
    isActive: Boolean = true
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) {
            Primary.copy(alpha = 0.1f)
        } else {
            Color.White.copy(alpha = 0.05f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (isActive) {
                Primary.copy(alpha = 0.3f)
            } else {
                Color.White.copy(alpha = 0.1f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = command.trigger,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isActive) Primary else OnBackgroundSubtle,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = command.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnBackgroundSubtle,
                    fontSize = 12.sp
                )
                if (command.example.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Example: \"${command.example}\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnBackgroundSubtle.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            }
            if (isActive) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Active",
                    tint = Primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * List of voice command cards
 */
@Composable
fun VoiceCommandList(
    commands: List<VoiceCommand>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Available Voice Commands",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        commands.forEach { command ->
            VoiceCommandCard(
                command = command,
                isActive = true
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun VoiceCommandCardPreview() {
    ProntafonTheme {
        VoiceCommandCard(
            command = VoiceCommand(
                trigger = "hello computer",
                description = "Wake up voice assistant",
                example = "Hello computer, what's the weather?"
            ),
            isActive = true
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun VoiceCommandCardInactivePreview() {
    ProntafonTheme {
        VoiceCommandCard(
            command = VoiceCommand(
                trigger = "stop listening",
                description = "Pause speech recognition",
                example = "Stop listening please"
            ),
            isActive = false
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun VoiceCommandListPreview() {
    ProntafonTheme {
        Column(Modifier.padding(16.dp)) {
            VoiceCommandList(
                commands = listOf(
                    VoiceCommand(
                        trigger = "hello computer",
                        description = "Activate voice assistant",
                        example = "Hello computer"
                    ),
                    VoiceCommand(
                        trigger = "send message",
                        description = "Send a message",
                        example = "Send message to John"
                    ),
                    VoiceCommand(
                        trigger = "stop listening",
                        description = "Pause recognition",
                        example = "Stop listening"
                    )
                )
            )
        }
    }
}
