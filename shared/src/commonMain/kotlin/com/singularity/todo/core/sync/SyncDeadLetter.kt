package com.singularity.todo.core.sync

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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
 * they share a table and one owner-scoped erase.
 *
 * ## Why the wrong kind cannot be re-queued by accident
 *
 * Re-queueing an inbound event would send this device's own copy of somebody else's
 * change as a local patch. That is not left to a comment, and not left to a caller
 * remembering the prefix. Three things hold the kinds apart:
 *
 * - the key prefix is the kind, and [SyncDeadLetterEntity.isInboundEvent] is the one
 *   place that reads it;
 * - [SyncDeadLetterDao.delete] — the primitive a restore uses to take a row off the
 *   shelf and put it back on the outbox — carries `patch_id NOT LIKE 'pull:%'` in its
 *   SQL. An inbound key therefore deletes nothing and returns 0, so a restore that
 *   reached one would report "there was nothing there" instead of sending somebody
 *   else's change back out as this device's patch;
 * - the only statements that can remove an inbound row are [SyncDeadLetterDao.deleteForOwner]
 *   and [SyncDeadLetterDao.clearAll], and both discard the record without sending
 *   anything.
 *
 * There is no restore path in this codebase yet. This is the contract it will be
 * written against, so that the first one cannot pick up an inbound row by mistake.
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
    /**
     * Whether this row is an inbound event that could not be read, rather than an
     * outbound patch that could not be delivered.
     */
    val isInboundEvent: Boolean
        get() = isInboundEventKey(patchId)

    companion object {
        /**
         * Marks a key as an inbound event rather than a patch id.
         *
         * A patch id is generated locally and does not carry this prefix; it is what
         * makes the two kinds distinguishable inside one table, and it is what
         * [SyncDeadLetterDao.delete] filters on.
         */
        const val INBOUND_EVENT_KEY_PREFIX: String = "pull:"

        /** Whether [key] is an inbound-event key rather than a patch id. */
        fun isInboundEventKey(key: String): Boolean = key.startsWith(INBOUND_EVENT_KEY_PREFIX)

        /**
         * The key of a shelved inbound event that could not be read.
         *
         * The prefix is what tells the two kinds of row apart — see the entity's own
         * KDoc — and the rest is the event's own identity.
         *
         * ## Why the owner is part of the key
         *
         * The insert replaces on this key ([SyncDeadLetterDao.insert]), and the shelf
         * keeps rows across a sign-out (REQ-UA-006), so two accounts can be looking at
         * the same profile. Without the owner, an event for account B would resolve to
         * the same key as a shelved event of account A — same profile, same server
         * position, same entity — and replace it. The row would then claim to belong to
         * B while its payload was still A's document: `deleteForOwner(A)` would leave
         * A's data behind (REQ-UA-017), and A's payload would surface on B's settings
         * screen.
         *
         * Including the owner keeps the replace inside one account, which is the only
         * place it is wanted: a redelivery of the same event to the same account is the
         * same row, so it updates rather than accumulating a copy per cycle.
         *
         * A profile reassigned from one owner to another keeps the key it was written
         * with. That costs at most a duplicate if the event is redelivered to the new
         * owner — bounded by the pull shelf's cap — and never costs an overwrite.
         */
        fun corruptEventId(
            ownerId: String,
            event: SyncEvent,
        ): String =
            INBOUND_EVENT_KEY_PREFIX +
                "$ownerId:${event.profileId}:${event.serverLsn}:${event.entityType.key}:${event.entityId}"
    }
}

/**
 * How many unreadable inbound events the shelf keeps, at most.
 *
 * The push-side shelf is bounded by its own terms: a row exists because a patch this
 * device made exhausted its retry budget, so there is at most one row per change the
 * person made. The pull-side shelf has no such bound — the producer is the server's
 * feed. A feed that is corrupt wholesale would otherwise write every event's payload
 * into SQLite, one row per LSN, and `watch()` / `getAll()` / `watchCount()` read the
 * whole table, so the settings badge and list would grow without limit on a phone.
 *
 * A cap rather than a TTL. A TTL deletes evidence on a clock the user cannot see and
 * cannot argue with; a cap keeps the newest [PULL_SHELF_MAX_ROWS] — which is what
 * somebody diagnosing the device actually wants — and says, in the log and in the
 * count, that older ones were dropped. The rows are pure evidence: nothing retries
 * them, so a dropped one costs a record, not a change that could have been delivered.
 */
const val PULL_SHELF_MAX_ROWS: Int = 200

/**
 * DAO for patches that exhausted their retries.
 */
@Dao
interface SyncDeadLetterDao {
    /**
     * Watch the shelf, newest first — for a settings screen.
     *
     * Holds both kinds of row, so a dropped remote change is something a person can
     * find as well as a set-aside patch. The table is bounded (see
     * [PULL_SHELF_MAX_ROWS] for the inbound half, and the outbox's own retry budget for
     * the outbound one), so this read does not grow without limit.
     */
    @Query("SELECT * FROM sync_dead_letter ORDER BY failed_at DESC")
    fun watch(): Flow<List<SyncDeadLetterEntity>>

    @Query("SELECT * FROM sync_dead_letter ORDER BY failed_at DESC")
    suspend fun getAll(): List<SyncDeadLetterEntity>

    @Query("SELECT COUNT(*) FROM sync_dead_letter")
    fun watchCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SyncDeadLetterEntity)

    /**
     * Removes an outbound patch row, so the patch can go back on the outbox and be
     * sent again.
     *
     * ## The predicate is the guard
     *
     * `patch_id NOT LIKE 'pull:%'` is not decoration: this is the primitive a restore
     * path calls, and an inbound-event key deletes nothing here. That matters because
     * an inbound row is not a patch — it is a remote change this device could not read
     * — and putting it back on the outbox would send this device's own copy of somebody
     * else's change as a local patch. A restore that reached one now gets 0 and reports
     * "there was nothing there" instead.
     *
     * Inbound rows are removed only by [deleteForOwner] and [clearAll], neither of
     * which sends anything.
     *
     * @return how many rows were removed, so a caller can tell "restored" from
     *   "there was nothing there". An explicit retry of a patch that is not on the
     *   shelf should say so rather than appear to succeed.
     */
    @Query("DELETE FROM sync_dead_letter WHERE patch_id = :id AND patch_id NOT LIKE 'pull:%'")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM sync_dead_letter")
    suspend fun clearAll()

    /**
     * Drops an account's shelved rows, and only that account's.
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

/**
 * The shelf that stands in when none was wired into the pull path.
 *
 * It used to be a `null` default on the pull phase's constructor, and that made the
 * omission silent: the recording half of this feature could be left out at a call site
 * and the only trace of a corrupt payload would be a log line — exactly the behaviour
 * the shelf exists to replace. This object cannot be silent about it: every write it is
 * asked for is logged at error level, by name.
 *
 * It is still a default rather than a hard requirement because the pull loop is
 * exercised on its own in tests, with no database behind it. Production wiring — the
 * only place this matters — hands over the real DAO as a non-optional constructor
 * argument of [SyncEngine], which passes it on.
 */
internal object NoDeadLetterShelf : SyncDeadLetterDao {
    private val log = Logger.withTag("DeadLetterShelf")

    override fun watch(): Flow<List<SyncDeadLetterEntity>> = flowOf(emptyList())

    override suspend fun getAll(): List<SyncDeadLetterEntity> = emptyList()

    override fun watchCount(): Flow<Int> = flowOf(0)

    override suspend fun insert(entity: SyncDeadLetterEntity) {
        log.e {
            "No dead-letter shelf is wired into the pull phase: ${entity.patchId} " +
                "was NOT recorded (${entity.lastError})"
        }
    }

    override suspend fun delete(id: String): Int = 0

    override suspend fun clearAll() = Unit

    override suspend fun deleteForOwner(ownerId: String): Int = 0

    override suspend fun countForOwner(ownerId: String): Int = 0
}
