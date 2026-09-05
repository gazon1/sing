package com.singularity.todo.feature.tasks.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags

/**
 * Title and description inputs for the task editor.
 * Title is a large borderless field (TickTick-style) that auto-focuses on New mode.
 */
@Composable
fun TaskEditorTitleSection(
    title: String,
    description: String,
    isError: Boolean,
    errorMessage: String?,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    requestFocus: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            focusRequester.requestFocus()
        }
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            placeholder = {
                Text(
                    text = "Task title",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            isError = isError,
            supportingText = errorMessage?.let {
                { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TestTags.TASK_EDITOR_ERROR)) }
            },
            textStyle = MaterialTheme.typography.headlineSmall,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag(TestTags.TASK_EDITOR_TITLE_INPUT),
        )

        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            placeholder = {
                Text(
                    "Description (optional)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            minLines = 2,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag(TestTags.TASK_EDITOR_DESCRIPTION_INPUT),
        )
    }
}
