package com.singularity.todo.core.ui.detail

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * An inline-editable text field used inside detail screens for title and description.
 *
 * Mimics the appearance of a plain `Text` composable until focused, then becomes
 * editable. Replaces `TaskTitleRow` and `TaskDescriptionField` patterns.
 *
 * @param value Current text value.
 * @param onValueChange Called when the text changes (debounce handled by caller).
 * @param placeholder Shown when value is empty and not focused.
 * @param textStyle Base text style (controls size, font weight, etc.).
 * @param singleLine Whether to restrict to a single line.
 * @param modifier Modifier for the field.
 */
@Composable
fun InlineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    singleLine: Boolean = true,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        singleLine = singleLine,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = textStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            innerTextField()
        },
    )
}
