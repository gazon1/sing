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
 * A patch that failed [PatchRetryPolicy.maxAttempts] times and was set aside.
 *
 * Set aside, never deleted. The alternative — a patch that has been retried ten times
 * in a row failing silently — is strictly worse than one that is visibly stuck: a user
 * can act on a failure they can see, and a change they made should not be destroyed
 * because the system could not deliver it.
 *
 * Lives in its own table, and behind its own DAO, rather than as more columns or
 * queries on the outbox. A queue of things waiting to be sent and a shelf of things
 * that gave up are different things with different owners, and merging them means
 * every push query has to remember to exclude the shelf.
 */
@Entity(tableName = "sync_dead_letter")
data class SyncDeadLetterEntity(
    @PrimaryKey @ColumnInfo("patch_id") val patchId: String,
    @ColumnInfo("entity_id") val entityId: String,
    @ColumnInfo("entity_type") val entityType: String,
    val payload: String,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("failed_at") val failedAt: Long,
    val attempts: Int,
    @ColumnInfo("last_error") val lastError: String? = null,
)

/**
 * DAO for patches that exhausted their retries.
 */
@Dao
interface SyncDeadLetterDao {
    /** Watch dead-lettered patches, newest first — for a settings screen. */
    @Query("SELECT * FROM sync_dead_letter ORDER BY failed_at DESC")
    fun watch(): Flow<List<SyncDeadLetterEntity>>

    @Query("SELECT * FROM sync_dead_letter ORDER BY failed_at DESC")
    suspend fun getAll(): List<SyncDeadLetterEntity>

    @Query("SELECT COUNT(*) FROM sync_dead_letter")
    fun watchCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SyncDeadLetterEntity)

    /**
     * Removes a dead-lettered patch so it can be put back on the outbox.
     *
     * @return how many rows were removed, so a caller can tell "restored" from
     *   "there was nothing there". An explicit retry of a patch that is not on the
     *   shelf should say so rather than appear to succeed.
     */
    @Query("DELETE FROM sync_dead_letter WHERE patch_id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM sync_dead_letter")
    suspend fun clearAll()
}
