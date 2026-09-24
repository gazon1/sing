package com.singularity.todo.core.attachments

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    // ─── UserId-scoped reads (Phase 2.8 fix) ──────────────────────────────────
    @Query(
        "SELECT * FROM attachments WHERE task_id = :taskId AND user_id = :userId AND deleted_at IS NULL ORDER BY created_at DESC",
    )
    fun watchByTaskForUser(taskId: String, userId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE id = :id AND user_id = :userId")
    fun watchByIdForUser(id: String, userId: String): Flow<AttachmentEntity?>

    @Query("SELECT * FROM attachments WHERE user_id = :userId AND deleted_at IS NULL ORDER BY created_at DESC")
    fun watchAll(userId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE id = :id AND user_id = :userId AND deleted_at IS NULL")
    suspend fun getById(id: String, userId: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE sync_status = :status AND user_id = :userId AND deleted_at IS NULL")
    fun watchBySyncStatusForUser(status: String, userId: String): Flow<List<AttachmentEntity>>

    // ─── Legacy (internal / Phase 3 migration target) ───────────────────────
    @Query("SELECT * FROM attachments WHERE task_id = :taskId AND deleted_at IS NULL ORDER BY created_at DESC")
    fun watchByTask(taskId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE id = :id")
    fun watchById(id: String): Flow<AttachmentEntity?>

    @Upsert
    suspend fun upsert(entity: AttachmentEntity)

    @Query("UPDATE attachments SET deleted_at = :ts, updated_at = :ts WHERE id = :id")
    suspend fun softDelete(id: String, ts: Long)

    @Query("SELECT * FROM attachments WHERE sync_status = :status AND deleted_at IS NULL")
    fun watchBySyncStatus(status: String): Flow<List<AttachmentEntity>>

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM attachments WHERE user_id = :userId")
    suspend fun listAllForUser(userId: String): List<AttachmentEntity>
}
