package com.singularity.todo.core.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.preview.noopClick

/**
 * Generic "AI Result" / error dialog.
 *
 * Replaces four near-identical inline `AlertDialog` blocks previously living
 * in TasksScreen, NoteEditorScreen, ProjectsScreen, and ChatScreen.
 */
@Composable
fun ResultDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        // Flows assert the dialog is present, not what it says: these messages are
        // user-facing copy that changes with the feature and is translated on this
        // device, so matching the text rots.
        modifier = Modifier
            .testTag(TestTags.RESULT_DIALOG)
            .mapTestTagsAsResourceIds(),
    )
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ResultDialogSuccessPreview() = PreviewThemed(darkTheme = false) {
    ResultDialog(
        title = "Success",
        text = "Your data has been exported successfully.",
        onDismiss = noopClick,
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ResultDialogErrorPreview() = PreviewThemed(darkTheme = true) {
    ResultDialog(
        title = "Error",
        text = "Failed to connect to the server. Please check your internet connection.",
        onDismiss = noopClick,
    )
}
