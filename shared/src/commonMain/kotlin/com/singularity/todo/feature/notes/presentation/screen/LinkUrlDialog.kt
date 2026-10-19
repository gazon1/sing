package com.singularity.todo.feature.notes.presentation.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.preview.noopClick
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

@Suppress("FunctionSignature")
@Composable
fun LinkUrlDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val focusManager = LocalFocusManager.current
    val urlState = rememberTextFieldState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert Link") },
        text = {
            OutlinedTextField(
                state = urlState,
                label = { Text("URL") },
                placeholder = { Text("https://example.com") },
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                onKeyboardAction = {
                    focusManager.clearFocus()
                    val url = urlState.text.toString()
                    if (url.isNotBlank()) onConfirm(url.trim())
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    focusManager.clearFocus()
                    val url = urlState.text.toString()
                    if (url.isNotBlank()) onConfirm(url.trim())
                },
                enabled = urlState.text.isNotBlank(),
            ) {
                Text("Insert")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                focusManager.clearFocus()
                onDismiss()
            }) {
                Text("Cancel")
            }
        },
    )
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun LinkUrlDialogPreview() = PreviewThemed(darkTheme = false) {
    LinkUrlDialog(
        onConfirm = { },
        onDismiss = noopClick,
    )
}
