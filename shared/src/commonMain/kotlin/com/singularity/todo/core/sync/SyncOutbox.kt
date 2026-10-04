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
 *
 * [attempts] and [nextAttemptAt] are what keep a failing patch from being retried
 * forever. The first version of this table had [attempts] and nothing read it, so a
 * patch the server kept rejecting was re-sent on every cycle with no delay and no
 * deadline — the worst possible retry policy against a server that is already
 * struggling, and a guaranteed amplifier of whatever was wrong.
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
    /**
     * Epoch millis before which this patch must not be sent. Null means "now".
     * Backoff writes it; the push query filters on it.
     */
    @ColumnInfo("next_attempt_at") val nextAttemptAt: Long? = null,
)

/**
 * DAO for sync outbox operations.
 */
@Dao
interface SyncOutboxDao {
    /** Watch all pending patches ordered by creation time */
    @Query("SELECT * FROM sync_outbox ORDER BY created_at ASC")
    fun watchPending(): Flow<List<SyncOutboxEntity>>

    /**
     * Patches eligible for a push right now, oldest first.
     *
     * The [nextAttemptAt] filter is what makes backoff real: without it a failed
     * patch is re-sent on the very next cycle regardless of how recently it failed.
     */
    @Query(
        "SELECT * FROM sync_outbox " +
            "WHERE next_attempt_at IS NULL OR next_attempt_at <= :now " +
            "ORDER BY created_at ASC",
    )
    suspend fun getPending(now: Long): List<SyncOutboxEntity>

    /** Insert a new patch */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SyncOutboxEntity)

    /** Delete a patch by ID */
    @Query("DELETE FROM sync_outbox WHERE patch_id = :id")
    suspend fun delete(id: String)

    /**
     * Records a failed attempt and the earliest time it may be retried.
     *
     * `attempts + 1` in SQL rather than a value from Kotlin so that two cycles that
     * overlap cannot both read the same count and write the same increment.
     */
    @Query(
        "UPDATE sync_outbox SET attempts = attempts + 1, last_error = :error, " +
            "next_attempt_at = :nextAttemptAt WHERE patch_id = :id",
    )
    suspend fun markFailed(id: String, error: String, nextAttemptAt: Long)

    /** Current attempt count for a patch. */
    @Query("SELECT attempts FROM sync_outbox WHERE patch_id = :id")
    suspend fun attemptsOf(id: String): Int?

    /** Delete all patches for an entity */
    @Query("DELETE FROM sync_outbox WHERE entity_id = :entityId")
    suspend fun deleteByEntity(entityId: String)

    /** Clear the entire outbox */
    @Query("DELETE FROM sync_outbox")
    suspend fun clearAll()
}

/**
 * Retry policy for a patch the server refused.
 *
 * Delay grows exponentially and is capped, and after [maxAttempts] the patch leaves
 * the outbox for the dead-letter store. The cap matters as much as the growth: an
 * uncapped doubling reaches an interval longer than the feature's lifetime, which
 * is a way of saying "never" while looking like a policy.
 *
 * The first attempt is retried after [baseDelay], not immediately. Retrying a
 * rejected patch straight away is how one bad row becomes a hot loop.
 */
class PatchRetryPolicy(
    private val baseDelayMs: Long = 30_000L,
    private val maxDelayMs: Long = 60 * 60 * 1000L,
    val maxAttempts: Int = 10,
) {
    /** Delay before attempt number [attempts] (1-based: the first failure is 1). */
    fun delayFor(attempts: Int): Long {
        require(attempts >= 1) { "attempts must be at least 1, was $attempts" }
        val shift = (attempts - 1).coerceAtMost(MAX_SHIFT)
        val delay = baseDelayMs shl shift
        return if (delay < 0 || delay > maxDelayMs) maxDelayMs else delay
    }

    /** Whether a patch that has failed [attempts] times has run out of retries. */
    fun isExhausted(attempts: Int): Boolean = attempts >= maxAttempts

    private companion object {
        /** 2^30 ms is about 12 days; beyond that the shift is pointless. */
        const val MAX_SHIFT = 30
    }
}
