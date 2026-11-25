package com.singularity.todo.test.fakes

import com.singularity.todo.feature.ai.chat.persistence.ChatConversation
import com.singularity.todo.feature.ai.chat.persistence.ChatConversationId
import com.singularity.todo.feature.ai.chat.persistence.ChatConversationRepository
import com.singularity.todo.feature.ai.chat.persistence.ChatMessageId
import com.singularity.todo.feature.ai.chat.persistence.ChatMessageRecord
import com.singularity.todo.feature.ai.chat.persistence.ChatMessageStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeChatConversationRepository(
    initialConversations: List<ChatConversation> = emptyList(),
    initialMessages: List<ChatMessageRecord> = emptyList(),
) : ChatConversationRepository {
    private val conversations = MutableStateFlow(initialConversations.associateBy { it.id })
    private val messages = MutableStateFlow(initialMessages.associateBy { it.id })

    val insertedMessages: List<ChatMessageRecord>
        get() = messages.value.values.sortedBy { it.position }

    val updatedMessages: MutableList<ChatMessageRecord> = mutableListOf()

    override fun observeLatestConversation(): Flow<ChatConversation?> =
        conversations.map { map ->
            map.values.maxWithOrNull(compareBy<ChatConversation> { it.updatedAt }.thenBy { it.createdAt }.thenBy { it.id.value })
        }

    override fun observeMessages(conversationId: ChatConversationId): Flow<List<ChatMessageRecord>> =
        messages.map { map ->
            map.values
                .filter { it.conversationId == conversationId }
                .sortedWith(compareBy<ChatMessageRecord> { it.position }.thenBy { it.createdAt }.thenBy { it.id.value })
        }

    override suspend fun getMessages(conversationId: ChatConversationId): List<ChatMessageRecord> =
        messages.value.values
            .filter { it.conversationId == conversationId }
            .sortedWith(compareBy<ChatMessageRecord> { it.position }.thenBy { it.createdAt }.thenBy { it.id.value })

    override suspend fun createConversation(conversation: ChatConversation): Result<Unit> = runCatching {
        conversations.update { it + (conversation.id to conversation) }
    }

    override suspend fun insertMessage(message: ChatMessageRecord): Result<Unit> = runCatching {
        messages.update { it + (message.id to message) }
    }

    override suspend fun updateMessageContent(
        messageId: ChatMessageId,
        conversationId: ChatConversationId,
        content: String,
        status: ChatMessageStatus,
        updatedAt: Long,
    ): Result<Unit> = runCatching {
        val current = messages.value[messageId] ?: error("Chat message not found: ${messageId.value}")
        require(current.conversationId == conversationId) { "Chat message not found: ${messageId.value}" }
        val updated = current.copy(content = content, status = status, updatedAt = updatedAt)
        messages.update { it + (messageId to updated) }
        updatedMessages += updated
    }

    override suspend fun markInProgressInterrupted(conversationId: ChatConversationId, updatedAt: Long): Result<Int> =
        runCatching {
            var changed = 0
            messages.update { map ->
                map.mapValues { (_, message) ->
                    if (message.conversationId == conversationId && message.status == ChatMessageStatus.InProgress) {
                        changed++
                        val updated = message.copy(status = ChatMessageStatus.Interrupted, updatedAt = updatedAt)
                        updatedMessages += updated
                        updated
                    } else {
                        message
                    }
                }
            }
            changed
        }

    override suspend fun touchConversation(conversationId: ChatConversationId, updatedAt: Long): Result<Unit> = runCatching {
        val current = conversations.value[conversationId] ?: error("Chat conversation not found: ${conversationId.value}")
        conversations.update { it + (conversationId to current.copy(updatedAt = updatedAt)) }
    }
}
