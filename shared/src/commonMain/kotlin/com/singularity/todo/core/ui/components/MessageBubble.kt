package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.preview.PreviewThemed

/** Speaker of a chat message bubble. */
enum class BubbleRole { User, Assistant }

/** Pure formatter kept separate so it can be unit-tested without Compose. */
internal fun bubbleLabel(role: BubbleRole): String = when (role) {
    BubbleRole.User -> "You"
    BubbleRole.Assistant -> "Assistant"
}

/**
 * Single canonical chat-bubble renderer used by AI Chat screens.
 *
 * Replaces two near-identical implementations that previously lived in
 * `feature/ai/chat/ChatScreen.kt` (Card + container colors) and
 * `feature/ai/chat/ChatScreen.kt` (column with role label).
 */
@Composable
fun MessageBubble(role: BubbleRole, content: String, modifier: Modifier = Modifier) {
    val isUser = role == BubbleRole.User
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = bubbleLabel(role),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
        Box(modifier = Modifier.fillMaxWidth(0.85f)) {
            Text(
                text = content.ifBlank { "…" },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun MessageBubbleUserLightPreview() = PreviewThemed(darkTheme = false) {
    MessageBubble(role = BubbleRole.User, content = "Can you help me organize my tasks?")
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun MessageBubbleAssistantDarkPreview() = PreviewThemed(darkTheme = true) {
    MessageBubble(
        role = BubbleRole.Assistant,
        content = "Sure! I can help you prioritize your tasks based on due dates and priority levels.",
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun MessageBubbleUserDarkPreview() = PreviewThemed(darkTheme = true) {
    MessageBubble(role = BubbleRole.User, content = "What about my project tasks?")
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun MessageBubbleAssistantPurpleDarkPreview() = PreviewThemed(
    darkTheme = true,
    accent = com.singularity.todo.core.ui.theme.SingularityAccents.Purple,
) {
    MessageBubble(role = BubbleRole.Assistant, content = "I've organized them by priority. Check your Inbox!")
}
