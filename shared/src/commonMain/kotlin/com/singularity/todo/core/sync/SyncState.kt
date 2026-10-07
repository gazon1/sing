package com.singularity.todo.core.sync

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * The identity that sync state belongs to.
 *
 * ## Why a pair and not one id
 *
 * The download cursor is a position in a server's event log, and that log is per
 * `(owner, profile)`. Storing one cursor for the whole app is only correct while there
 * is exactly one of each — so a second profile, or a second account on a device that
 * has already synced the first, resumes from a position that belongs to a different
 * data set. Everything after that point is applied against the wrong history, and
 * nothing reports it, because a pull that applies a hundred events successfully looks
 * exactly like a pull that started in the right place.
 *
 * `ownerId` is the signed-in account; `profileId` is the local profile whose data is
 * being synchronised. They are separate columns rather than a concatenated key string
 * so that the database can index and query them, and so that a mistake in one is
 * visible as a mistake rather than as a cache key.
 */
data class SyncScope(val ownerId: String, val profileId: String) {
    init {
        require(ownerId.isNotBlank()) { "ownerId must not be blank" }
        require(profileId.isNotBlank()) { "profileId must not be blank" }
    }
}

/**
 * Per-scope sync state: where this scope's download stopped, when it last synced, and
 * the preferences that drive it.
 *
 * Replaces the flat DataStore keys, which could express "a cursor" but not "a cursor
 * per scope" without concatenating a key name — and whose writes were not
 * transactional with the entity writes they describe.
 */
@Entity(tableName = "sync_state", primaryKeys = ["owner_id", "profile_id"])
data class SyncStateEntity(
    @ColumnInfo("owner_id") val ownerId: String,
    @ColumnInfo("profile_id") val profileId: String,
    /** Last log sequence number that was actually applied. Not "received". */
    @ColumnInfo("last_lsn") val lastLsn: Long = 0,
    @ColumnInfo("last_successful_sync_at") val lastSuccessfulSyncAt: Long? = null,
    /**
     * Identifies this install to the server.
     *
     * Preserved across a sign-out: the device does not become a different device, and
     * regenerating this would make the server treat the next sync as a fresh client.
     */
    @ColumnInfo("device_id") val deviceId: String? = null,
    @ColumnInfo("auto_sync_enabled") val autoSyncEnabled: Boolean = true,
    @ColumnInfo("scheduled_interval_minutes") val scheduledIntervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    /** Comma-separated [SyncTrigger] names; empty means "all". */
    @ColumnInfo("enabled_triggers") val enabledTriggers: String = "",
    /**
     * Whether this scope's pre-existing local data has already been queued for
     * upload (REQ-OS-013).
     *
     * Per scope, not per device: the requirement is that a *second sign-in on the
     * same account* uploads nothing further, and a flag keyed by anything local
     * would answer a different question. Set only after the enqueue completes, so
     * an interrupted seed resumes rather than being declared done.
     */
    @ColumnInfo("seed_completed", defaultValue = "0") val seedCompleted: Boolean = false,
    /**
     * Whether this scope uploads attachment payloads alongside its documents.
     *
     * Per scope, for the same reason as [autoSyncEnabled]: the data the flag
     * describes belongs to one account/profile pair, so a global flag would let
     * one profile's choice describe another's attachments.
     *
     * Defaults to `false` rather than to "on": there is no binary transport yet
     * (see ADR `2026-10-07-attachment-sync-is-staged-behind-a-server-blocker`),
     * so the only value that can be honoured today is "off". Storing `true` would
     * record a promise the client cannot keep.
     */
    @ColumnInfo("attachments_sync_enabled", defaultValue = "0")
    val attachmentsSyncEnabled: Boolean = false,
) {
    val triggers: Set<SyncTrigger>
        get() = SyncTrigger.parseCsv(enabledTriggers)

    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 30
    }
}

/**
 * DAO for [SyncStateEntity], keyed by owner and profile.
 *
 * Reads use a nullable argument and fall back to a default row rather than returning
 * null: sync has to work on a device that has never synced this scope, and a missing
 * row is the normal first-run state, not an error to propagate.
 */
// One function per settable column is what a DAO is; splitting it to satisfy a count
// would scatter one table's write surface across several interfaces and make the
// cross-field invariants harder to read, not easier. The twelfth member is
// `attachmentsSyncEnabled`, which is scoped like every other setting on this table.
@Suppress("TooManyFunctions")
@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE owner_id = :ownerId AND profile_id = :profileId")
    suspend fun get(ownerId: String, profileId: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE owner_id = :ownerId AND profile_id = :profileId")
    fun observe(ownerId: String, profileId: String): Flow<SyncStateEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: SyncStateEntity)

    @Query("UPDATE sync_state SET last_lsn = :lsn WHERE owner_id = :ownerId AND profile_id = :profileId")
    suspend fun setLastLsn(ownerId: String, profileId: String, lsn: Long)

    @Query(
        "UPDATE sync_state SET last_successful_sync_at = :at " +
            "WHERE owner_id = :ownerId AND profile_id = :profileId",
    )
    suspend fun setLastSuccessfulSyncAt(ownerId: String, profileId: String, at: Long)

    @Query(
        "UPDATE sync_state SET device_id = :deviceId " +
            "WHERE owner_id = :ownerId AND profile_id = :profileId",
    )
    suspend fun setDeviceId(ownerId: String, profileId: String, deviceId: String)

    @Query(
        "UPDATE sync_state SET auto_sync_enabled = :enabled " +
            "WHERE owner_id = :ownerId AND profile_id = :profileId",
    )
    suspend fun setAutoSyncEnabled(ownerId: String, profileId: String, enabled: Boolean)

    @Query(
        "UPDATE sync_state SET scheduled_interval_minutes = :minutes " +
            "WHERE owner_id = :ownerId AND profile_id = :profileId",
    )
    suspend fun setScheduledIntervalMinutes(ownerId: String, profileId: String, minutes: Int)

    @Query(
        "UPDATE sync_state SET enabled_triggers = :triggers " +
            "WHERE owner_id = :ownerId AND profile_id = :profileId",
    )
    suspend fun setEnabledTriggers(ownerId: String, profileId: String, triggers: String)

    @Query(
        "UPDATE sync_state SET seed_completed = :completed " +
            "WHERE owner_id = :ownerId AND profile_id = :profileId",
    )
    suspend fun setSeedCompleted(ownerId: String, profileId: String, completed: Boolean)

    @Query(
        "UPDATE sync_state SET attachments_sync_enabled = :enabled " +
            "WHERE owner_id = :ownerId AND profile_id = :profileId",
    )
    suspend fun setAttachmentsSyncEnabled(ownerId: String, profileId: String, enabled: Boolean)

    @Query("DELETE FROM sync_state")
    suspend fun clearAll()
}
