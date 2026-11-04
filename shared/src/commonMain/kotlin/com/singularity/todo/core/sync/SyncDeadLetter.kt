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
 *
 * ## Two kinds of row
 *
 * The shelf holds two things, and the key says which is which:
 *
 * - an **outbound patch** this device could not deliver — the key is the patch id, and
 *   the row can be put back on the outbox and sent again;
 * - an **inbound event** this device could not read — the key is [corruptEventId], and
 *   the row cannot be retried, because the server sends the same bytes on every
 *   delivery.
 *
 * Both are "something gave up", and both belong to the account that owns the work, so
 * they share a table and one owner-scoped erase. A reader that means to retry a row
 * has to ask which kind it is: re-queueing an inbound event would send this device's
 * own copy of somebody else's change.
 */
@Entity(tableName = "sync_dead_letter")
data class SyncDeadLetterEntity(
    @PrimaryKey @ColumnInfo("patch_id") val patchId: String,
    /**
     * The account this patch belongs to.
     *
     * Required and without a default, so every construction site has to state who the
     * work is for. It was optional for the life of this table, and the queue was
     * therefore unattributable: a switch could not deliver one account's set-aside work
     * without risking another's, and an owner-scoped erase had nothing to scope to —
     * it would have taken the incoming account's work with the outgoing one's. #209.
     */
    @ColumnInfo("owner_id", defaultValue = "''") val ownerId: String,
    @ColumnInfo("entity_id") val entityId: String,
    @ColumnInfo("entity_type") val entityType: String,
    val payload: String,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("failed_at") val failedAt: Long,
    val attempts: Int,
    @ColumnInfo("last_error") val lastError: String? = null,
) {
    companion object {
        /**
         * The key of a shelved inbound event that could not be read.
         *
         * The prefix is what tells the two kinds of row apart — see the entity's own
         * KDoc — and the rest is the event's own identity: the profile it belonged to,
         * the server position it arrived at, and the document it names. A redelivery of
         * the same event is the same row, so the insert replaces it rather than
         * accumulating a copy per cycle.
         */
        fun corruptEventId(event: SyncEvent): String =
            "pull:${event.profileId}:${event.serverLsn}:${event.entityType.key}:${event.entityId}"
    }
}

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
     * Removes a dead-lettered row.
     *
     * For an outbound patch this is how it is put back on the outbox and sent again.
     * For an inbound event there is nothing to put back — the server sends the same
     * bytes on every delivery — so removing the row only discards the record.
     *
     * @return how many rows were removed, so a caller can tell "restored" from
     *   "there was nothing there". An explicit retry of a patch that is not on the
     *   shelf should say so rather than appear to succeed.
     */
    @Query("DELETE FROM sync_dead_letter WHERE patch_id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM sync_dead_letter")
    suspend fun clearAll()

    /**
     * Drops an account's shelved patches, and only that account's.
     *
     * REQ-UA-017's erase. Without the owner filter this would take the incoming
     * account's shelved work with it — the shelf can hold rows from several accounts,
     * since REQ-UA-006 keeps work across a sign-out.
     */
    @Query("DELETE FROM sync_dead_letter WHERE owner_id = :ownerId")
    suspend fun deleteForOwner(ownerId: String): Int

    /** Count rows owned by [ownerId], without deleting. For preview before erase. */
    @Query("SELECT COUNT(*) FROM sync_dead_letter WHERE owner_id = :ownerId")
    suspend fun countForOwner(ownerId: String): Int
}
