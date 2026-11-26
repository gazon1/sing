package com.singularity.todo.feature.ai.chat.persistence

import kotlinx.coroutines.flow.Flow

/**
 * App-owned persistence boundary for the built-in chat transcript.
 *
 * The repository stores conversation metadata and ordered messages. It does not
 * own agent execution, tool calling, prompt replay, or streaming. The ViewModel
 * remains the orchestration layer and uses this port to persist/restore transcript
 * state around the existing turn lifecycle.
 */
interface ChatConversationRepository {
    /** Observe the most recently updated conversation for the current scoped user. */
    fun observeLatestConversation(): Flow<ChatConversation?>

    /** Observe messages for [conversationId] in deterministic position order. */
    fun observeMessages(conversationId: ChatConversationId): Flow<List<ChatMessageRecord>>

    /** Read messages for [conversationId] once, in deterministic position order. */
    suspend fun getMessages(conversationId: ChatConversationId): List<ChatMessageRecord>

    /** Create a conversation owned by the current scoped user. */
    suspend fun createConversation(conversation: ChatConversation): Result<Unit>

    /** Insert a message row. The caller provides a unique position in the conversation. */
    suspend fun insertMessage(message: ChatMessageRecord): Result<Unit>

    /** Update assistant message content/status while preserving ownership and ordering. */
    suspend fun updateMessageContent(
        messageId: ChatMessageId,
        conversationId: ChatConversationId,
        content: String,
        status: ChatMessageStatus,
        updatedAt: Long,
    ): Result<Unit>

    /**
     * Reconcile stale in-progress assistant messages after process restart/cancellation.
     *
     * Messages left in [ChatMessageStatus.InProgress] cannot still be executing after
     * process death. They are marked [ChatMessageStatus.Interrupted] and their partial
     * content is preserved.
     */
    suspend fun markInProgressInterrupted(
        conversationId: ChatConversationId,
        updatedAt: Long,
    ): Result<Int>

    /** Touch conversation updatedAt after transcript changes. */
    suspend fun touchConversation(
        conversationId: ChatConversationId,
        updatedAt: Long,
    ): Result<Unit>
}
