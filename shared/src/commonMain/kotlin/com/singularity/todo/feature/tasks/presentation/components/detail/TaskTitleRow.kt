package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
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
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import com.singularity.todo.feature.tasks.presentation.theme.placeholderTextColor
import androidx.compose.material3.MaterialTheme

/**
 * Заголовок задачи: чекбокс завершения + инлайн-редактируемое поле названия.
 * Клик по чекбоксу — отдельный target от поля ввода (не пересекаются).
 *
 * [onCheckToggle] is nullable and `null` hides the checkbox entirely. Create mode
 * passed `{}`, which rendered a completion checkbox — the screen's most prominent
 * control — that did nothing when pressed. There is no task to complete before it
 * exists, so the honest rendering is no checkbox, not a dead one.
 */
@Composable
fun TaskTitleRow(
    taskId: String,
    title: String,
    isCompleted: Boolean,
    onTitleChange: (String) -> Unit,
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
            value = title,
            onValueChange = onTitleChange,
            textStyle = LocalTextStyle.current.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .testTag(TestTags.TASK_EDITOR_TITLE_INPUT),
            decorationBox = { innerTextField ->
                if (title.isEmpty()) {
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
