package com.prontafon.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.prontafon.presentation.theme.*

/**
 * Button component for requesting permissions
 * Shows icon, title, description, and action button
 */
@Composable
fun PermissionButton(
    title: String,
    description: String,
    icon: ImageVector,
    buttonText: String,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
    isGranted: Boolean = false
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isGranted) {
            Connected.copy(alpha = 0.1f)
        } else {
            Warning.copy(alpha = 0.1f)
        }
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) Connected else Warning,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = OnBackgroundSubtle,
                textAlign = TextAlign.Center
            )
            if (!isGranted) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        contentColor = OnPrimary
                    )
                ) {
                    Text(buttonText)
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "✓ Granted",
                    style = MaterialTheme.typography.labelMedium,
                    color = Connected
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun PermissionButtonNotGrantedPreview() {
    ProntafonTheme {
        PermissionButton(
            title = "Microphone Permission",
            description = "Required to capture your voice for speech recognition",
            icon = Icons.Default.Settings,
            buttonText = "Grant Permission",
            onRequestPermission = {},
            isGranted = false
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun PermissionButtonGrantedPreview() {
    ProntafonTheme {
        PermissionButton(
            title = "Microphone Permission",
            description = "Required to capture your voice for speech recognition",
            icon = Icons.Default.Settings,
            buttonText = "Grant Permission",
            onRequestPermission = {},
            isGranted = true
        )
    }
}
