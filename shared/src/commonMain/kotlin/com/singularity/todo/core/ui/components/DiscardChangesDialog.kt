package com.singularity.todo.core.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Confirmation dialog shown when the user tries to navigate away from an unsaved edit.
 *
 * @param onDiscard          Called when the user confirms discarding.
 * @param onKeepEditing     Called when the user chooses to keep editing.
 * @param title              Dialog title text.
 * @param text               Dialog body text.
 * @param discardButtonText  Label for the confirm/discard button.
 * @param keepEditingButtonText Label for the cancel/keep-editing button.
 */
@Composable
fun DiscardChangesDialog(
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
    title: String = "Discard changes?",
    text: String = "You have unsaved changes. If you leave now, your changes will be lost.",
    discardButtonText: String = "Discard",
    keepEditingButtonText: String = "Keep editing",
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(discardButtonText)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) {
                Text(keepEditingButtonText)
            }
        },
    )
}
