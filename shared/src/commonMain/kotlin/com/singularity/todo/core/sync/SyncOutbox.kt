package com.singularity.todo.core.sync

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Outbox entity for pending sync patches.
 */
@Entity(tableName = "sync_outbox")
data class SyncOutboxEntity(
    @PrimaryKey @ColumnInfo("patch_id") val patchId: String,
    @ColumnInfo("entity_id") val entityId: String,
    @ColumnInfo("entity_type") val entityType: String,
    val payload: String, // Serialized DeltaPatch JSON
    @ColumnInfo("created_at") val createdAt: Long,
    val attempts: Int = 0,
    @ColumnInfo("last_error") val lastError: String? = null,
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
    @Query("DELETE FROM sync_outbox WHERE patch_id = :id")
    suspend fun delete(id: String)

    /** Mark patch as failed and increment attempts */
    @Query("UPDATE sync_outbox SET attempts = attempts + 1, last_error = :error WHERE patch_id = :id")
    suspend fun markFailed(id: String, error: String)

    /** Delete all patches for an entity */
    @Query("DELETE FROM sync_outbox WHERE entity_id = :entityId")
    suspend fun deleteByEntity(entityId: String)

    /** Clear the entire outbox */
    @Query("DELETE FROM sync_outbox")
    suspend fun clearAll()
}
