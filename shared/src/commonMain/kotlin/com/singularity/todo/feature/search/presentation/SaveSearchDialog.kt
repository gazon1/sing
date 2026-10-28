package com.singularity.todo.feature.search.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
 * Dialog for naming and saving the current search.
 *
 * @param initialName Pre-filled name suggestion from the current query string.
 * @param onDismiss Called when the user cancels.
 * @param onSave Called with the trimmed name when the user confirms.
 */
@Composable
fun SaveSearchDialog(initialName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val nameState = rememberTextFieldState(initialName)

    AlertDialog(
        onDismissRequest = onDismiss,
        // A dialog is its own Android window, so the app-root
        // testTagsAsResourceId never reaches it. Without this the tag on
        // the confirm button is invisible to Maestro.
        modifier = Modifier.mapTestTagsAsResourceIds(),
        title = { Text("Save Search") },
        text = {
            Column {
                OutlinedTextField(
                    state = nameState,
                    label = { Text("Search name") },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(nameState.text.toString().trim()) },
                enabled = nameState.text.toString().trim().isNotBlank(),
                modifier = Modifier
                    .testTag(TestTags.SavedSearches.SAVE_DIALOG_CONFIRM)
                    .mapTestTagsAsResourceIds(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Dialog for renaming an existing saved search.
 */
@Composable
fun RenameSearchDialog(currentName: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    val nameState = rememberTextFieldState(currentName)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Search") },
        text = {
            OutlinedTextField(
                state = nameState,
                label = { Text("Search name") },
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(nameState.text.toString().trim()) },
                enabled = nameState.text.toString().trim().isNotBlank(),
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun SaveSearchDialogPreview() = PreviewThemed(darkTheme = false) {
    SaveSearchDialog(
        initialName = "overdue tasks",
        onDismiss = noopClick,
        onSave = { },
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun RenameSearchDialogPreview() = PreviewThemed(darkTheme = true) {
    RenameSearchDialog(
        currentName = "overdue tasks",
        onDismiss = noopClick,
        onRename = { },
    )
}
