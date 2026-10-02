package com.singularity.todo.core.ui.detail

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A confirmation dialog for destructive or irreversible actions.
 *
 * Replaces `AlertDialog` instances used for delete/archive confirmation on detail screens.
 * Labels are passed as parameters to avoid hardcoded strings in shared code.
 *
 * @param title The title of the confirmation dialog.
 * @param message The explanatory text shown below the title.
 * @param confirmLabel Label for the confirm button (e.g. "Delete").
 * @param dismissLabel Label for the cancel button (e.g. "Cancel").
 * @param onConfirm Called when the user confirms the action.
 * @param onDismiss Called when the user dismisses the dialog (Cancel or back).
 * @param modifier Modifier for the dialog.
 */
@Composable
fun ConfirmationSheet(
    title: String,
    message: String,
    confirmLabel: String = "Delete",
    dismissLabel: String = "Cancel",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel)
            }
        },
    )
}
