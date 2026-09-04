package com.singularity.todo.core.ui.components

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
