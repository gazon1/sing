package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
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
 * @param titleState the title's field state. The owner built the source and bridged it,
 *   and the kind of that source — static or owned — is decided by the caller, at compile
 *   time. This component shows the field and does not decide who may rewrite it.
 */
@Composable
fun TaskTitleRow(
    taskId: String,
    titleState: TextFieldState,
    isCompleted: Boolean,
    onCheckToggle: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
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
                // One tag per node. A second `testTag` on the same chain is
                // applied and then dropped by the semantics merge, so the flow
                // that asks for it can never pass — the tag looks right in
                // `TestTags.kt` and exists nowhere in the tree. The title's
                // *value* needs no second name: the field publishes it as the
                // node's text (measured on the device, 2026-10-28), so a flow
                // asserts it with `text:`.
                .testTag(TestTags.TASK_EDITOR_TITLE_INPUT),
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
        titleState = TextFieldState("Buy groceries"),
        isCompleted = false,
        onCheckToggle = noopClick,
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskTitleRowCompletedPreview() = PreviewThemed(darkTheme = false) {
    TaskTitleRow(
        taskId = "t1",
        titleState = TextFieldState("Buy groceries"),
        isCompleted = true,
        onCheckToggle = noopClick,
    )
}
