package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.preview.PreviewThemed

/**
 * Text-input row with a send button. Drives the AI chat screen and any other
 * streaming-input surface.
 *
 * The field owns its text: the caller passes a [TextFieldState] it has created (typically via
 * `rememberBridgedTextFieldState`, so the chat ViewModel keeps the last submitted text without
 * owning the buffer). The parent decides when `onSend` is allowed to fire (typically gated on
 * `enabled`).
 */
@Composable
fun ChatInputBar(
    state: TextFieldState,
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
            state = state,
            modifier = Modifier
                .weight(1f)
                .testTag(TestTags.CHAT_INPUT),
            placeholder = { Text(placeholder) },
            lineLimits = if (maxLines == 1) {
                TextFieldLineLimits.SingleLine
            } else {
                TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = maxLines)
            },
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
        state = rememberTextFieldState(),
        onSend = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ChatInputBarWithTextDarkPreview() = PreviewThemed(darkTheme = true) {
    ChatInputBar(
        state = rememberTextFieldState("What should I work on today?"),
        onSend = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ChatInputBarDisabledPreview() = PreviewThemed(darkTheme = false) {
    ChatInputBar(
        state = rememberTextFieldState("Thinking..."),
        onSend = {},
        enabled = false,
    )
}
