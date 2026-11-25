package com.singularity.todo.feature.ai.chat.persistence

import com.singularity.todo.core.ids.nextId
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/** Identity of one persisted chat conversation. */
@Serializable
@JvmInline
value class ChatConversationId(val value: String) {
    companion object {
        fun generate() = ChatConversationId(nextId())

        fun fromString(value: String) = ChatConversationId(value)
    }
}

/** Identity of one persisted chat message. */
@Serializable
@JvmInline
value class ChatMessageId(val value: String) {
    companion object {
        fun generate() = ChatMessageId(nextId())

        fun fromString(value: String) = ChatMessageId(value)
    }
}

/** Who sent a persisted chat message. */
enum class PersistedChatRole {
    User,
    Assistant,
}

/**
 * Lifecycle of a persisted assistant message.
 *
 * [InProgress] means the turn was still generating when it was last written.
 * After process death or cancellation, reconciliation turns it into [Interrupted]
 * while keeping the partial content. [Completed] means the turn finished normally.
 */
enum class ChatMessageStatus {
    InProgress,
    Completed,
    Interrupted,
}

/** Conversation metadata. The transcript lives in [ChatMessageRecord] rows. */
data class ChatConversation(
    val id: ChatConversationId,
    val userId: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One persisted message in a conversation.
 *
 * [position] gives deterministic ordering within the owning conversation.
 * [status] tracks assistant generation lifecycle; user messages are [ChatMessageStatus.Completed].
 */
data class ChatMessageRecord(
    val id: ChatMessageId,
    val conversationId: ChatConversationId,
    val userId: String,
    val role: PersistedChatRole,
    val content: String,
    val status: ChatMessageStatus,
    val position: Int,
    val createdAt: Long,
    val updatedAt: Long,
)
