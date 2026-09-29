package com.singularity.todo.core.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.singularity.todo.core.ui.TestTags

/**
 * Generic confirmation dialog with customizable title, text, and button labels.
 *
 * ```
 * ConfirmActionDialog(
 *     title = "Delete view?",
 *     text = "This action cannot be undone.",
 *     confirmButtonText = "Delete",
 *     onConfirm = { /* delete */ },
 *     onDismiss = { /* cancel */ },
 * )
 * ```
 *
 * @param title          Dialog title text.
 * @param text           Dialog body text.
 * @param confirmButtonText Label for the confirm/destructive button.
 * @param onConfirm      Called when the user confirms.
 * @param onDismiss      Called when the user cancels or dismisses.
 * @param dismissButtonText Label for the cancel button (defaults to "Cancel").
 */
@Composable
fun ConfirmActionDialog(
    title: String,
    text: String,
    confirmButtonText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissButtonText: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(TestTags.Dialog.CONFIRM),
            ) {
                Text(confirmButtonText)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(TestTags.Dialog.DISMISS),
            ) {
                Text(dismissButtonText)
            }
        },
    )
}
