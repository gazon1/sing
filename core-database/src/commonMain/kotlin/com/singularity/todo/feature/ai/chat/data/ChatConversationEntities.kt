package com.singularity.todo.feature.ai.chat.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One built-in chat conversation.
 *
 * Conversations are scoped by [userId] (profile-scoped user id) so profile
 * switching cannot leak transcript rows across profiles.
 */
@Entity(
    tableName = "chat_conversations",
    indices = [
        Index("user_id"),
        Index(value = ["user_id", "updated_at"]),
    ],
)
data class ChatConversationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("user_id") val userId: String,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
)

/**
 * One message in a built-in chat conversation.
 *
 * [position] provides deterministic ordering and is unique per conversation.
 * [status] stores the assistant lifecycle: InProgress, Completed, Interrupted.
 * User messages are stored as Completed.
 */
@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversation_id"),
        Index("user_id"),
        Index(value = ["conversation_id", "position"], unique = true),
        Index(value = ["conversation_id", "status"]),
    ],
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("user_id") val userId: String,
    @ColumnInfo("role") val role: String,
    @ColumnInfo("content") val content: String,
    @ColumnInfo("status") val status: String,
    @ColumnInfo("position") val position: Int,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
)

@Dao
interface ChatConversationDao {
    @Upsert
    suspend fun upsertConversation(entity: ChatConversationEntity)

    @Query(
        """
        SELECT * FROM chat_conversations
        WHERE user_id = :userId
        ORDER BY updated_at DESC, created_at DESC
        LIMIT 1
        """,
    )
    fun observeLatestForUser(userId: String): Flow<ChatConversationEntity?>

    @Query("SELECT * FROM chat_conversations WHERE id = :id AND user_id = :userId LIMIT 1")
    suspend fun getByIdForUser(
        id: String,
        userId: String,
    ): ChatConversationEntity?

    @Query(
        """
        UPDATE chat_conversations
        SET updated_at = :updatedAt
        WHERE id = :id AND user_id = :userId
        """,
    )
    suspend fun touchForUser(
        id: String,
        userId: String,
        updatedAt: Long,
    ): Int

    @Query("SELECT * FROM chat_conversations WHERE user_id = :userId ORDER BY updated_at DESC, created_at DESC")
    suspend fun listAllForUser(userId: String): List<ChatConversationEntity>

    @Query("DELETE FROM chat_conversations WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String): Int
}

@Dao
interface ChatMessageDao {
    @Upsert
    suspend fun upsertMessage(entity: ChatMessageEntity)

    @Query(
        """
        SELECT * FROM chat_messages
        WHERE conversation_id = :conversationId AND user_id = :userId
        ORDER BY position ASC, created_at ASC, id ASC
        """,
    )
    fun observeByConversationForUser(
        conversationId: String,
        userId: String,
    ): Flow<List<ChatMessageEntity>>

    @Query(
        """
        SELECT * FROM chat_messages
        WHERE conversation_id = :conversationId AND user_id = :userId
        ORDER BY position ASC, created_at ASC, id ASC
        """,
    )
    suspend fun listByConversationForUser(
        conversationId: String,
        userId: String,
    ): List<ChatMessageEntity>

    @Query(
        """
        SELECT COALESCE(MAX(position), -1)
        FROM chat_messages
        WHERE conversation_id = :conversationId AND user_id = :userId
        """,
    )
    suspend fun maxPositionForUser(
        conversationId: String,
        userId: String,
    ): Int

    @Query(
        """
        UPDATE chat_messages
        SET content = :content, status = :status, updated_at = :updatedAt
        WHERE id = :id AND conversation_id = :conversationId AND user_id = :userId
        """,
    )
    suspend fun updateContentForUser(
        id: String,
        conversationId: String,
        userId: String,
        content: String,
        status: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE chat_messages
        SET status = :status, updated_at = :updatedAt
        WHERE conversation_id = :conversationId AND user_id = :userId AND status = :inProgressStatus
        """,
    )
    suspend fun markInProgressForUser(
        conversationId: String,
        userId: String,
        inProgressStatus: String,
        status: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        SELECT * FROM chat_messages
        WHERE user_id = :userId
        ORDER BY conversation_id ASC, position ASC, created_at ASC, id ASC
        """,
    )
    suspend fun listAllForUser(userId: String): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String): Int
}
