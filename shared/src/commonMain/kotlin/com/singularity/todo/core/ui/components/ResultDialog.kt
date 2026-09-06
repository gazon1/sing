package com.singularity.todo.core.ui.components

import com.singularity.todo.core.ui.preview.PreviewThemed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Generic "AI Result" / error dialog. Shows nothing when [text] is null.
 *
 * Replaces four near-identical inline `AlertDialog` blocks previously living
 * in TasksScreen, NoteEditorScreen, ProjectsScreen, and ChatScreen.
 */
@Composable
fun ResultDialog(
    title: String,
    text: String?,
    onDismiss: () -> Unit,
) {
    if (text == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ResultDialogSuccessPreview() = PreviewThemed(darkTheme = false) {
    ResultDialog(
        title = "Success",
        text = "Your data has been exported successfully.",
        onDismiss = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ResultDialogErrorPreview() = PreviewThemed(darkTheme = true) {
    ResultDialog(
        title = "Error",
        text = "Failed to connect to the server. Please check your internet connection.",
        onDismiss = {},
    )
}
