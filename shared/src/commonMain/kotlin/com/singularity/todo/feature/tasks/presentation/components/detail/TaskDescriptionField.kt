package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.components.rememberBridgedTextFieldState
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import com.singularity.todo.feature.tasks.presentation.theme.placeholderTextColor

/**
 * Многострочное поле описания. Реальный текстовый ввод вместо статичного
 * Text — в оригинале это выглядело как кнопка, а не поле ввода.
 */
@Composable
fun TaskDescriptionField(description: String, onDescriptionChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val descriptionState = rememberBridgedTextFieldState(
        externalText = description,
        onTextChanged = onDescriptionChange,
    )
    BasicTextField(
        state = descriptionState,
        textStyle = LocalTextStyle.current.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = TaskSpacing.sm),
        decorator = { innerTextField ->
            if (descriptionState.text.isEmpty()) {
                Text(
                    text = "Введите описание задачи...",
                    color = placeholderTextColor(),
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                )
            }
            innerTextField()
        },
    )
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskDescriptionFieldPreview() = PreviewThemed(darkTheme = false) {
    TaskDescriptionField(
        description = "Pick up the oat milk and the olive oil.",
        onDescriptionChange = { },
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun TaskDescriptionFieldEmptyPreview() = PreviewThemed(darkTheme = false) {
    TaskDescriptionField(
        description = "",
        onDescriptionChange = { },
    )
}
