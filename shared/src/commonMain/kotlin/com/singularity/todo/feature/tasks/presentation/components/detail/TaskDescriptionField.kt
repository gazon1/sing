package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import com.singularity.todo.feature.tasks.presentation.theme.placeholderTextColor
import org.jetbrains.compose.resources.stringResource
import singularity_todo.shared.generated.resources.Res
import singularity_todo.shared.generated.resources.task_description_placeholder

/**
 * Многострочное поле описания. Реальный текстовый ввод вместо статичного
 * Text — в оригинале это выглядело как кнопка, а не поле ввода.
 *
 * @param descriptionState the description's field state. The owner built the source and
 *   bridged it, and the kind of that source — static or owned — is decided by the caller,
 *   at compile time. This component shows the field and does not decide who may rewrite it.
 */
@Composable
fun TaskDescriptionField(
    descriptionState: TextFieldState,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        state = descriptionState,
        textStyle =
            MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier =
            modifier
                .fillMaxWidth()
                .bringIntoViewOnFocus()
                .padding(vertical = TaskSpacing.sm)
                .testTag(TestTags.TASK_EDITOR_DESCRIPTION_INPUT)
                .semantics { contentDescription = "Task description" },
        decorator = { innerTextField ->
            if (descriptionState.text.isEmpty()) {
                Text(
                    text = stringResource(Res.string.task_description_placeholder),
                    color = placeholderTextColor(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            innerTextField()
        },
    )
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskDescriptionFieldPreview() =
    PreviewThemed(darkTheme = false) {
        TaskDescriptionField(
            descriptionState = TextFieldState("Pick up the oat milk and the olive oil."),
        )
    }

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskDescriptionFieldEmptyPreview() =
    PreviewThemed(darkTheme = false) {
        TaskDescriptionField(
            descriptionState = TextFieldState(""),
        )
    }
