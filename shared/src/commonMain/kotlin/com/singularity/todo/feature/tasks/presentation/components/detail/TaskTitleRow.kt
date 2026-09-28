package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.singularity.todo.feature.tasks.presentation.theme.TaskColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing

/**
 * Заголовок задачи: чекбокс завершения + инлайн-редактируемое поле названия.
 * Клик по чекбоксу — отдельный target от поля ввода (не пересекаются).
 */
@Composable
fun TaskTitleRow(
    title: String,
    isCompleted: Boolean,
    onTitleChange: (String) -> Unit,
    onCheckToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCheckToggle) {
            Icon(
                imageVector = if (isCompleted) Icons.Filled.CheckCircle else Icons.Outlined.CheckBoxOutlineBlank,
                contentDescription = if (isCompleted) "Задача выполнена" else "Отметить как выполненную",
                tint = if (isCompleted) TaskColors.AccentBlue else TaskColors.TextSecondary,
                modifier = Modifier.size(TaskSpacing.iconSizeLarge),
            )
        }
        Spacer(modifier = Modifier.width(TaskSpacing.md))
        BasicTextField(
            value = title,
            onValueChange = onTitleChange,
            textStyle = LocalTextStyle.current.copy(
                color = TaskColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            cursorBrush = SolidColor(TaskColors.AccentBlue),
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .testTag(TestTags.TASK_EDITOR_TITLE_INPUT),
            decorationBox = { innerTextField ->
                if (title.isEmpty()) {
                    Text(
                        text = "Название задачи",
                        color = TaskColors.TextPlaceholder,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                innerTextField()
            },
        )
    }
}
