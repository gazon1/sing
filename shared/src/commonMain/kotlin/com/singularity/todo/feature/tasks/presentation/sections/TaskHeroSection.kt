package com.singularity.todo.feature.tasks.presentation.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tasks.presentation.components.TaskDetailActions

/**
 * The top "hero" area of a task detail screen: a large checkbox, inline-editable
 * title, inline-editable description, and kind/someday indicator buttons.
 *
 * @param title        Current task title (may be empty — placeholder is shown).
 * @param isCompleted  Completion state — drives checkbox and strikethrough.
 * @param actions      Packed [TaskDetailActions] callback handler.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskTitle(
    title: String,
    isCompleted: Boolean,
    actions: TaskDetailActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ){
        Column {
            Checkbox(
                checked = isCompleted,
                onCheckedChange = { actions.onToggleComplete() },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Column {
            InlineEditableText(
                value = title,
                placeholder = "Task title",
                style = MaterialTheme.typography.titleLarge,
                textDecoration = if (isCompleted) TextDecoration.LineThrough else null,
                onValueChange = actions::onTitleChange,
            )
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDescription(
    description: String?,
    actions: TaskDetailActions,
    modifier: Modifier = Modifier,
) {
    InlineEditableText(
        value = description ?: "",
        placeholder = "Add description...",
        style = MaterialTheme.typography.bodyMedium.copy(
            color = if (description.isNullOrBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        textDecoration = null,
        onValueChange = actions::onDescriptionChange,
    )
}


/**
 * A single-line text field that shows a styled placeholder when empty and
 * switches seamlessly to an editable state — used for title and description.
 */

@Composable
fun InlineEditableText(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    singleLine: Boolean = true,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onFocusChanged: (Boolean) -> Unit = {},
    imeAction: ImeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChanged(it.isFocused) },
        singleLine = singleLine,
        textStyle = style.copy(
            color = MaterialTheme.colorScheme.onSurface,
            textDecoration = textDecoration,
            textAlign = textAlign ?: TextAlign.Start,
        ),
        placeholder = {
            Text(
                text = placeholder,
                style = style.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textDecoration = textDecoration,
                    textAlign = textAlign ?: TextAlign.Start,
                ),
                maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            )
        },
        // Убираем рамку и делаем фон прозрачным, чтобы поле «сливалось» со строкой
        shape = RoundedCornerShape(ZeroCornerSize),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onDone = { onImeAction?.invoke() ?: focusManager.clearFocus() },
            onGo = { onImeAction?.invoke() },
            onNext = { onImeAction?.invoke() },
        ),
    )
}

// ===== Preview =====

@Preview
@Composable
private fun TaskTitleLightPreview() = PreviewThemed(darkTheme = false) {
    TaskTitle(
        title = "Review pull request",
        isCompleted = false,
        actions = TaskDetailActions.Empty,
    )
}

@Preview
@Composable
private fun TaskTitleDarkPreview() = PreviewThemed(darkTheme = true) {
    TaskTitle(
        title = "Someday idea",
        isCompleted = false,
        actions = TaskDetailActions.Empty,
    )
}

@Preview
@Composable
private fun TaskDescriptionLightPreview() = PreviewThemed(darkTheme = false) {
    TaskDescription(
        description = "Check the implementation and leave feedback",
        actions = TaskDetailActions.Empty,
    )
}

@Preview
@Composable
private fun TaskDescriptionDarkPreview() = PreviewThemed(darkTheme = true) {
    TaskDescription(
        description = null,
        actions = TaskDetailActions.Empty,
    )
}

@Preview
@Composable
private fun TaskTitleCompletedPreview() = PreviewThemed(darkTheme = false) {
    TaskTitle(
        title = "Completed task",
        isCompleted = true,
        actions = TaskDetailActions.Empty,
    )
}
