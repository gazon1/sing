package com.singularity.todo.feature.ai.chat.persistence

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.feature.ai.chat.data.ChatConversationDao
import com.singularity.todo.feature.ai.chat.data.ChatConversationEntity
import com.singularity.todo.feature.ai.chat.data.ChatMessageDao
import com.singularity.todo.feature.ai.chat.data.ChatMessageEntity
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChatConversationRepositoryImpl(
    private val conversationDao: ChatConversationDao,
    private val messageDao: ChatMessageDao,
    private val currentUser: ProfileAwareCurrentUser,
) : ChatConversationRepository {
    override fun observeLatestConversation(): Flow<ChatConversation?> =
        currentUser.observeForCurrentUser { uid ->
            conversationDao.observeLatestForUser(uid.value).map { it?.toDomain() }
        }

    override fun observeMessages(conversationId: ChatConversationId): Flow<List<ChatMessageRecord>> =
        currentUser.observeForCurrentUser { uid ->
            messageDao.observeByConversationForUser(conversationId.value, uid.value).map { rows ->
                rows.map { it.toDomain() }
            }
        }

    override suspend fun getMessages(conversationId: ChatConversationId): List<ChatMessageRecord> {
        val uid = currentUser.scopedUserId.value.value
        return messageDao.listByConversationForUser(conversationId.value, uid).map { it.toDomain() }
    }

    override suspend fun createConversation(conversation: ChatConversation): Result<Unit> =
        runCatchingCancellable {
            val uid = currentUser.scopedUserId.value.value
            conversationDao.upsertConversation(
                conversation.copy(userId = uid).toEntity(),
            )
        }

    override suspend fun insertMessage(message: ChatMessageRecord): Result<Unit> =
        runCatchingCancellable {
            val uid = currentUser.scopedUserId.value.value
            messageDao.insertMessage(message.copy(userId = uid).toEntity())
        }

    override suspend fun updateMessageContent(
        messageId: ChatMessageId,
        conversationId: ChatConversationId,
        content: String,
        status: ChatMessageStatus,
        updatedAt: Long,
    ): Result<Unit> =
        runCatchingCancellable {
            val uid = currentUser.scopedUserId.value.value
            val rows =
                messageDao.updateContentForUser(
                    id = messageId.value,
                    conversationId = conversationId.value,
                    userId = uid,
                    content = content,
                    status = status.name,
                    updatedAt = updatedAt,
                )
            require(rows > 0) { "Chat message not found: ${messageId.value}" }
        }

    override suspend fun markInProgressInterrupted(
        conversationId: ChatConversationId,
        updatedAt: Long,
    ): Result<Int> =
        runCatchingCancellable {
            val uid = currentUser.scopedUserId.value.value
            messageDao.markInProgressForUser(
                conversationId = conversationId.value,
                userId = uid,
                inProgressStatus = ChatMessageStatus.InProgress.name,
                status = ChatMessageStatus.Interrupted.name,
                updatedAt = updatedAt,
            )
        }

    override suspend fun touchConversation(
        conversationId: ChatConversationId,
        updatedAt: Long,
    ): Result<Unit> =
        runCatchingCancellable {
            val uid = currentUser.scopedUserId.value.value
            val rows = conversationDao.touchForUser(conversationId.value, uid, updatedAt)
            require(rows > 0) { "Chat conversation not found: ${conversationId.value}" }
        }
}

private fun ChatConversationEntity.toDomain(): ChatConversation =
    ChatConversation(
        id = ChatConversationId.fromString(id),
        userId = userId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun ChatConversation.toEntity(): ChatConversationEntity =
    ChatConversationEntity(
        id = id.value,
        userId = userId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun ChatMessageEntity.toDomain(): ChatMessageRecord =
    ChatMessageRecord(
        id = ChatMessageId.fromString(id),
        conversationId = ChatConversationId.fromString(conversationId),
        userId = userId,
        role = PersistedChatRole.valueOf(role),
        content = content,
        status = ChatMessageStatus.valueOf(status),
        position = position,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun ChatMessageRecord.toEntity(): ChatMessageEntity =
    ChatMessageEntity(
        id = id.value,
        conversationId = conversationId.value,
        userId = userId,
        role = role.name,
        content = content,
        status = status.name,
        position = position,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
