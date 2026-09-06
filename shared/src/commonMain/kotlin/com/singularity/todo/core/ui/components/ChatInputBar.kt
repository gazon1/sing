package com.singularity.todo.core.ui.components

import com.singularity.todo.core.ui.preview.PreviewThemed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Text-input row with a send button. Drives the AI chat screen and any other
 * streaming-input surface. Stateless: parent owns `value` and decides when
 * `onSend` is allowed to fire (typically gated on `enabled`).
 */
@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean = true,
    placeholder: String = "Ask the AI...",
    maxLines: Int = 4,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(placeholder) },
            maxLines = maxLines,
            enabled = enabled,
        )
        IconButton(onClick = onSend, enabled = enabled) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ChatInputBarLightPreview() = PreviewThemed(darkTheme = false) {
    ChatInputBar(
        value = "",
        onValueChange = {},
        onSend = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ChatInputBarWithTextDarkPreview() = PreviewThemed(darkTheme = true) {
    ChatInputBar(
        value = "What should I work on today?",
        onValueChange = {},
        onSend = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ChatInputBarDisabledPreview() = PreviewThemed(darkTheme = false) {
    ChatInputBar(
        value = "Thinking...",
        onValueChange = {},
        onSend = {},
        enabled = false,
    )
}
