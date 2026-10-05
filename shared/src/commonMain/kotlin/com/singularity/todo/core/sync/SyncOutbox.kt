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
    /**
     * The account this patch belongs to.
     *
     * Required and without a default, so no construction site can leave it unset — the
     * failure mode this column exists to prevent is precisely a row nobody can attribute.
     *
     * Without it the outbox is a bag of work rather than per-account work: `getPending`
     * returned every row regardless of who wrote it, and `planPush` built one request
     * from all of them under the *active* scope. So work queued by one account left the
     * device inside another account's authenticated request — and REQ-UA-018, which
     * discards the answer, cannot un-send the request. #209.
     */
    @ColumnInfo("owner_id", defaultValue = "''") val ownerId: String,
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
     *
     * The `ownerId` filter is REQ-UA-019 and is not a refinement of the backoff filter
     * — it is what makes the result *addressable*. Queues from different accounts
     * coexist on purpose (REQ-UA-006 keeps work across a sign-out), so an unscoped
     * read is every account's work in one list, and the caller builds one request from
     * all of it under whichever scope happens to be active.
     */
    @Query(
        "SELECT * FROM sync_outbox " +
            "WHERE owner_id = :ownerId " +
            "AND (next_attempt_at IS NULL OR next_attempt_at <= :now) " +
            "ORDER BY created_at ASC",
    )
    suspend fun getPending(now: Long, ownerId: String): List<SyncOutboxEntity>

    /**
     * How many patches an account still owes the server.
     *
     * The switch needs this to know whether a delivery can be attempted at all, and
     * it has to be an owner's own count: a queue holding another account's work is not
     * a reason to refuse a switch, and treating it as one would make switching
     * impossible on any device that had ever held two accounts.
     */
    @Query("SELECT COUNT(*) FROM sync_outbox WHERE owner_id = :ownerId")
    suspend fun countPendingFor(ownerId: String): Int

    /**
     * Drops an account's whole queue, and only that account's.
     *
     * Part of REQ-UA-017's erase. Scoped by owner rather than by patch id because the
     * caller has no list of patch ids — the point is to remove what the owner queued,
     * and the owner is the only thing known about all of it.
     */
    @Query("DELETE FROM sync_outbox WHERE owner_id = :ownerId")
    suspend fun deleteForOwner(ownerId: String): Int

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

    /**
     * Delete all patches an account has queued for one entity.
     *
     * Owner-scoped because coalescing must not reach across accounts. Entity ids are
     * UUIDs so two accounts rarely share one, but "rarely" is not "never" — and the
     * unscoped form, if it ever fired, would have one account's enqueue silently
     * discard another account's unsent work, which is the failure REQ-UA-019 exists to
     * prevent, reached from the other direction.
     */
    @Query("DELETE FROM sync_outbox WHERE owner_id = :ownerId AND entity_id = :entityId")
    suspend fun deleteByEntity(ownerId: String, entityId: String)

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
