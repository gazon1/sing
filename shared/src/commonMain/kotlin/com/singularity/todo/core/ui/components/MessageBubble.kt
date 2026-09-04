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
 * `feature/ai/ChatScreen.kt` (Card + container colors) and
 * `feature/ai/chat/ChatScreen.kt` (column with role label).
 */
@Composable
fun MessageBubble(
    role: BubbleRole,
    content: String,
    modifier: Modifier = Modifier,
) {
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
