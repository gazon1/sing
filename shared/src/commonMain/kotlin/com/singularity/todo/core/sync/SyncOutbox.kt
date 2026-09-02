package com.singularity.todo.core.sync

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Outbox entity for pending sync patches.
 */
@Entity(tableName = "sync_outbox")
data class SyncOutboxEntity(
    @PrimaryKey val patchId: String,
    val entityId: String,
    val entityType: String,
    val payload: String,  // Serialized DeltaPatch JSON
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null
)

/**
 * DAO for sync outbox operations.
 */
@Dao
interface SyncOutboxDao {
    /** Watch all pending patches ordered by creation time */
    @Query("SELECT * FROM sync_outbox ORDER BY created_at ASC")
    fun watchPending(): Flow<List<SyncOutboxEntity>>

    /** Get all pending patches (non-Flow version) */
    @Query("SELECT * FROM sync_outbox ORDER BY created_at ASC")
    suspend fun getPending(): List<SyncOutboxEntity>

    /** Insert a new patch */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SyncOutboxEntity)

    /** Delete a patch by ID */
    @Query("DELETE FROM sync_outbox WHERE patchId = :id")
    suspend fun delete(id: String)

    /** Mark patch as failed and increment attempts */
    @Query("UPDATE sync_outbox SET attempts = attempts + 1, lastError = :error WHERE patchId = :id")
    suspend fun markFailed(id: String, error: String)

    /** Delete all patches for an entity */
    @Query("DELETE FROM sync_outbox WHERE entityId = :entityId")
    suspend fun deleteByEntity(entityId: String)

    /** Clear the entire outbox */
    @Query("DELETE FROM sync_outbox")
    suspend fun clearAll()
}
