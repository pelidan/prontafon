package com.prontafon.presentation.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.prontafon.presentation.theme.*

/**
 * Reusable confirmation dialog component
 * Shows title, message, and confirm/cancel buttons
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    cancelText: String = "Cancel",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDestructive: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = OnBackgroundMuted
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (isDestructive) Error else Primary
                )
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = OnBackgroundMuted
                )
            ) {
                Text(cancelText)
            }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = Surface,
        tonalElevation = 8.dp
    )
}

/**
 * Simple info dialog with single OK button
 */
@Composable
fun InfoDialog(
    title: String,
    message: String,
    buttonText: String = "OK",
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = OnBackgroundMuted
            )
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Primary
                )
            ) {
                Text(buttonText)
            }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = Surface,
        tonalElevation = 8.dp
    )
}

@Preview
@Composable
private fun ConfirmDialogPreview() {
    ProntafonTheme {
        ConfirmDialog(
            title = "Disconnect Device?",
            message = "Are you sure you want to disconnect from the current device?",
            confirmText = "Disconnect",
            cancelText = "Cancel",
            onConfirm = {},
            onDismiss = {},
            isDestructive = false
        )
    }
}

@Preview
@Composable
private fun ConfirmDialogDestructivePreview() {
    ProntafonTheme {
        ConfirmDialog(
            title = "Delete All History?",
            message = "This action cannot be undone. All your transcription history will be permanently deleted.",
            confirmText = "Delete",
            cancelText = "Cancel",
            onConfirm = {},
            onDismiss = {},
            isDestructive = true
        )
    }
}

@Preview
@Composable
private fun InfoDialogPreview() {
    ProntafonTheme {
        InfoDialog(
            title = "Pairing Complete",
            message = "Your device has been successfully paired and is ready to use.",
            buttonText = "Got it",
            onDismiss = {}
        )
    }
}
