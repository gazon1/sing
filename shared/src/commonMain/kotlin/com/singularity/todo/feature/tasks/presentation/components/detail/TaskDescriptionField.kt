package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.sp
import com.singularity.todo.feature.tasks.presentation.theme.TaskColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing

/**
 * Многострочное поле описания. Реальный текстовый ввод вместо статичного
 * Text — в оригинале это выглядело как кнопка, а не поле ввода.
 */
@Composable
fun TaskDescriptionField(
    description: String,
    onDescriptionChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = description,
        onValueChange = onDescriptionChange,
        textStyle = LocalTextStyle.current.copy(
            color = TaskColors.TextPrimary,
            fontSize = 16.sp,
            lineHeight = 22.sp
        ),
        cursorBrush = SolidColor(TaskColors.AccentBlue),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = TaskSpacing.sm),
        decorationBox = { innerTextField ->
            if (description.isEmpty()) {
                Text(
                    text = "Введите описание задачи...",
                    color = TaskColors.TextPlaceholder,
                    fontSize = 16.sp,
                    lineHeight = 22.sp
                )
            }
            innerTextField()
        }
    )
}
