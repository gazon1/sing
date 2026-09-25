package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Confirmation dialog for project archive/unarchive.
 */
@Composable
fun ConfirmArchiveSheet(isArchived: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isArchived) "Unarchive project?" else "Archive project?") },
        text = {
            Text(
                if (isArchived) "This will restore the project." else "Archived projects are hidden from the list.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(if (isArchived) "Unarchive" else "Archive")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
