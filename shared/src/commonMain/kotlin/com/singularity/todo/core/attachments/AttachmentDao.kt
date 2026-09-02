package com.singularity.todo.core.attachments

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
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
}
