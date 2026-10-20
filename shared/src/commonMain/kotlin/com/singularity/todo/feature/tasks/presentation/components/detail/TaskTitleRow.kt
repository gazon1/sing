package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.celebration.Celebration
import com.singularity.todo.core.ui.components.rememberBridgedTextFieldState
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.preview.noopClick
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import com.singularity.todo.feature.tasks.presentation.theme.placeholderTextColor

/**
 * Заголовок задачи: чекбокс завершения + инлайн-редактируемое поле названия.
 * Клик по чекбоксу — отдельный target от поля ввода (не пересекаются).
 *
 * [onCheckToggle] is nullable and `null` hides the checkbox entirely. Create mode
 * passed `{}`, which rendered a completion checkbox — the screen's most prominent
 * control — that did nothing when pressed. There is no task to complete before it
 * exists, so the honest rendering is no checkbox, not a dead one.
 *
 * @param revision the draft's revision counter. It moves when the task was rewritten from
 *   outside, and that is what tells the bridged field that the new title is a
 *   *replacement* rather than the echo of a keystroke the field itself produced.
 */
@Composable
fun TaskTitleRow(
    taskId: String,
    title: String,
    isCompleted: Boolean,
    onTitleChange: (String) -> Unit,
    onCheckToggle: (() -> Unit)?,
    revision: Long = 0,
    modifier: Modifier = Modifier,
) {
    val titleState = rememberBridgedTextFieldState(
        externalText = title,
        onTextChanged = onTitleChange,
        revision = revision,
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onCheckToggle != null) {
            Celebration(
                triggerKey = if (isCompleted) taskId else "",
            ) {
                IconButton(onClick = onCheckToggle) {
                    val doneTint = if (isCompleted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val glyph = if (isCompleted) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Outlined.CheckBoxOutlineBlank
                    }
                    Icon(
                        imageVector = glyph,
                        contentDescription = if (isCompleted) "Задача выполнена" else "Отметить как выполненную",
                        tint = doneTint,
                        modifier = Modifier.size(TaskSpacing.iconSizeLarge),
                    )
                }
            }
            Spacer(modifier = Modifier.width(TaskSpacing.md))
        }
        BasicTextField(
            state = titleState,
            textStyle = LocalTextStyle.current.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            lineLimits = TextFieldLineLimits.SingleLine,
            modifier = Modifier
                .weight(1f)
                .testTag(TestTags.TASK_EDITOR_TITLE_INPUT)
                .testTag(TestTags.TASK_DETAIL_TITLE),
            decorator = { innerTextField ->
                if (titleState.text.isEmpty()) {
                    Text(
                        text = "Название задачи",
                        color = placeholderTextColor(),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                innerTextField()
            },
        )
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskTitleRowPreview() = PreviewThemed(darkTheme = false) {
    TaskTitleRow(
        taskId = "t1",
        title = "Buy groceries",
        isCompleted = false,
        onTitleChange = { },
        onCheckToggle = noopClick,
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskTitleRowCompletedPreview() = PreviewThemed(darkTheme = false) {
    TaskTitleRow(
        taskId = "t1",
        title = "Buy groceries",
        isCompleted = true,
        onTitleChange = { },
        onCheckToggle = noopClick,
    )
}
