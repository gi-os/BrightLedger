package com.gios.brightledger.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.gios.brightledger.ui.theme.Dim

/**
 * A two-way confirm, in the same explicit dark container [MessageDialog] uses.
 *
 * The destructive action keeps the plain white label rather than a warning colour: the panel
 * is greyscale, so a red button is simply a grey button, and the only thing that can actually
 * signal weight here is the word.
 */
@Composable
fun ConfirmDialog(
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF141414),
        textContentColor = Color.White,
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = Color.White) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = Dim) }
        },
    )
}
