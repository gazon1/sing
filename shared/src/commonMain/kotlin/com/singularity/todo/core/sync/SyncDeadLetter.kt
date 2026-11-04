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
)

/**
 * DAO for patches that exhausted their retries.
 *
 * ## The guards, and where they come from
 *
 * The outbox is the reference, and this DAO wears the same guard set:
 *
 * - **global reads** — `watch`, `getAll`, `watchCount`. The shelf holds several
 *   accounts' work at once (REQ-UA-006 keeps work across a sign-out), and a read
 *   that is not scoped to an account is the outbox's `watchPending` under another
 *   name. The outbox's *sending* path is owner-scoped, and so is nothing here that
 *   moves a patch: the shelf's job is to hold work, not to deliver it.
 * - **owner-scoped erase and count** — `deleteForOwner`, `countForOwner`. REQ-UA-017's
 *   erase, and the preview that precedes it.
 * - **patch-id-keyed** — `delete`. A patch id is a UUID, so it names one row; the
 *   outbox's `delete` is keyed the same way.
 * - **global** — `clearAll`, the wholesale drain. No production path calls it: a
 *   sign-out keeps local data (REQ-UA-006, `SignOutKeepsLocalDataTest`), and the
 *   account switch erases one owner through `deleteForOwner`. It is the counterpart
 *   of the outbox's own `clearAll` — a reset with no owner to name — and not a
 *   substitute for the scoped erase.
 *
 * ## What the shelf does not carry
 *
 * No profile. The outbox row names the profile it belongs to and the shadow's key is
 * `(owner, profile)`, so a shelved patch is the one row of the three that cannot say
 * which profile it was queued under — and putting it back on the outbox therefore
 * cannot be done profile-correctly. That is a gap in the shelf's shape, not in its
 * guards, and it is a schema change rather than a query.
 */
@Dao
interface SyncDeadLetterDao {
    /**
     * Watch dead-lettered patches, newest first — for a settings screen.
     *
     * Every account's shelved work, like the outbox's `watchPending`. The shelf is
     * a transport queue that gave up, and the outbox's queue is read the same way.
     */
    @Query("SELECT * FROM sync_dead_letter ORDER BY failed_at DESC")
    fun watch(): Flow<List<SyncDeadLetterEntity>>

    /** Every shelved patch, newest first. Unscoped, as [watch] is. */
    @Query("SELECT * FROM sync_dead_letter ORDER BY failed_at DESC")
    suspend fun getAll(): List<SyncDeadLetterEntity>

    /** How many patches are shelved, across every account. Unscoped, as [watch] is. */
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

    /**
     * Drops every account's shelved patches.
     *
     * The wholesale drain, and no substitute for [deleteForOwner]: the account switch
     * erases one account's shelf and leaves the rest, while this empties every
     * account's. Nothing in production calls it — a sign-out keeps local data
     * (REQ-UA-006), so the shelf outlives the account that left — and it exists as the
     * counterpart of the outbox's `clearAll`, for a reset with no owner to name.
     *
     * A switch that reached for this would take the incoming account's shelved work
     * with the outgoing one's, which is the failure [deleteForOwner] exists to prevent.
     */
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
