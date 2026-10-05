package com.singularity.todo.feature.calendar_sync.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/**
 * Per-calendar sync cursor for the Google provider.
 *
 * ## Why one row per (user, provider, calendar)
 *
 * A Google account can have many calendars, and the incremental cursor is per calendar —
 * Google hands out a `nextSyncToken` for a specific calendar's change stream. Keeping them
 * in one row would mean a single calendar's invalidation resetting the others, and a
 * failure on one calendar stopping the rest.
 *
 * `provider` is in the key even though only `google` exists today, because the device
 * calendar's cursor is a different shape entirely and would otherwise need a parallel
 * table. See the ADR on the two-port design.
 *
 * ## Why `user_id` is in the primary key
 *
 * Unlike the app's own ULID-keyed tables — where a generated id is already a global
 * namespace, which is why `tasks`, `notes` and friends key on `id` alone — a Google
 * calendar id is an opaque string scoped to one account. Two profiles on the same device
 * can legitimately hold the *same* calendar id, so the key has to say which account it
 * belongs to.
 */
@Entity(
    tableName = "calendar_sync_state",
    primaryKeys = ["user_id", "provider", "calendar_id"],
)
data class CalendarSyncStateEntity(
    @ColumnInfo("user_id") val userId: String,
    @ColumnInfo("provider") val provider: String,
    @ColumnInfo("calendar_id") val calendarId: String,
    /**
     * Google's incremental cursor. Null means "never fully synced" — a caller must run a
     * full listing before it can use one.
     *
     * This is also the cheapest "is there anything new" test available: an unchanged token
     * means Google has no changes, which is a stronger and cheaper statement than any
     * hash of local state.
     */
    @ColumnInfo("next_sync_token") val nextSyncToken: String? = null,
    @ColumnInfo("last_full_sync_at") val lastFullSyncAt: Long? = null,
    @ColumnInfo("last_pull_at") val lastPullAt: Long? = null,
    @ColumnInfo("last_push_at") val lastPushAt: Long? = null,
)

/**
 * The last agreed field values for one Google event — the third input a 3-way merge needs.
 *
 * ## Why this table exists at all
 *
 * A checksum answers "did anything change?" but not "what changed?", and a merge needs the
 * common ancestor per field. Without a shadow there is no ancestor, the merge cannot tell
 * our change from theirs, and the only safe options are to do nothing (leaving the event
 * unsynced forever) or to overwrite a remote edit we never saw. Neither is acceptable, so
 * the ancestor is recorded.
 *
 * `task_id` is nullable: a foreign event the user has not converted has no task, and
 * `event_id` is the only thing we have to recognise it by.
 */
@Entity(
    tableName = "google_event_shadow",
    primaryKeys = ["user_id", "event_id"],
)
data class GoogleEventShadowEntity(
    @ColumnInfo("user_id") val userId: String,
    @ColumnInfo("event_id") val eventId: String,
    @ColumnInfo("task_id") val taskId: String? = null,
    @ColumnInfo("calendar_id") val calendarId: String,
    /**
     * Google's revision tag, replayed as `If-Match` on the next write so a concurrent
     * remote edit produces a 412 (re-read and re-plan) instead of a silent overwrite.
     */
    @ColumnInfo("etag") val etag: String? = null,
    /**
     * The ancestor's field values, serialised by
     * [com.singularity.todo.feature.calendar_sync.domain.logic.EventShadowCodec].
     *
     * Nullable fields inside the payload are load-bearing: a Google event with no
     * description and a task with an empty one are different states, and a format that
     * could not tell them apart would invent changes that never happened.
     */
    @ColumnInfo("base_json") val baseJson: String,
    @ColumnInfo("remote_updated_at") val remoteUpdatedAt: Long? = null,
    /**
     * When the user cancelled this event in Google, or null while it is live.
     *
     * A tombstone rather than a deleted row, and that is the whole point of the column: the
     * local-side push planner reads the shadow set to decide what still needs creating, so a
     * cancelled event with no shadow looks exactly like a task that has never been synced and
     * gets re-created — resurrecting what the user just removed. Keeping the row lets the
     * planner see "known, and gone on purpose" and leave it alone.
     */
    @ColumnInfo("cancelled_at") val cancelledAt: Long? = null,
    @ColumnInfo("last_synced_at") val lastSyncedAt: Long,
)

/**
 * A Google event the app did not create, offered to the user as something they can adopt.
 *
 * ## Why a separate table from the shadow
 *
 * A shadow is the *ancestor* of an event we already manage; this is the event itself, for
 * one that has no task. Merging them would mean one table holding "events we sync" and
 * "events we might import" with different nullability for `task_id`, and every query
 * would need to filter on it.
 */
@Entity(
    tableName = "calendar_import_event",
    primaryKeys = ["user_id", "event_id"],
)
data class CalendarImportEventEntity(
    @ColumnInfo("user_id") val userId: String,
    @ColumnInfo("event_id") val eventId: String,
    @ColumnInfo("calendar_id") val calendarId: String,
    @ColumnInfo("title") val title: String? = null,
    @ColumnInfo("description") val description: String? = null,
    @ColumnInfo("location") val location: String? = null,
    @ColumnInfo("starts_at") val startsAt: Long? = null,
    @ColumnInfo("ends_at") val endsAt: Long? = null,
    @ColumnInfo("all_day") val allDay: Boolean = false,
    /**
     * Google's RRULE, verbatim. Never normalised through the app's recurrence code — the
     * repository has no RFC 5545 implementation, and rewriting a rule would silently
     * change how many occurrences a series has.
     */
    @ColumnInfo("recurrence_rule") val recurrenceRule: String? = null,
    @ColumnInfo("etag") val etag: String? = null,
    @ColumnInfo("last_synced_at") val lastSyncedAt: Long,
    @ColumnInfo("imported_at") val importedAt: Long,
    /** Set once the user converts this into a real task; null while it is only an offer. */
    @ColumnInfo("task_id") val taskId: String? = null,
    /**
     * Non-null when this event needs a decision before anything is written — today only
     * the repeat rule can produce this, because a local regeneration can change how many
     * occurrences exist.
     */
    @ColumnInfo("conflicted_field") val conflictedField: String? = null,
)

/** Reads and writes [CalendarSyncStateEntity] for one (user, provider, calendar). */
@Dao
interface CalendarSyncStateDao {

    @Query(
        "SELECT * FROM calendar_sync_state " +
            "WHERE user_id = :userId AND provider = :provider AND calendar_id = :calendarId",
    )
    suspend fun get(userId: String, provider: String, calendarId: String): CalendarSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: CalendarSyncStateEntity)

    @Query("DELETE FROM calendar_sync_state WHERE user_id = :userId AND provider = :provider")
    suspend fun deleteForProvider(userId: String, provider: String)

    /**
     * Drops the cursor for one calendar and keeps every other calendar's.
     *
     * Used on `410 fullSyncRequired`. Scoped to a single calendar on purpose: one dead
     * token says nothing about the others, and resetting all of them would turn a small
     * invalidation into a full re-download of the account.
     */
    @Query(
        "UPDATE calendar_sync_state SET next_sync_token = NULL " +
            "WHERE user_id = :userId AND provider = :provider AND calendar_id = :calendarId",
    )
    suspend fun invalidateToken(userId: String, provider: String, calendarId: String)
}

/** Reads and writes [GoogleEventShadowEntity]. */
@Dao
interface GoogleEventShadowDao {

    @Query("SELECT * FROM google_event_shadow WHERE user_id = :userId AND event_id = :eventId")
    suspend fun get(userId: String, eventId: String): GoogleEventShadowEntity?

    @Query("SELECT * FROM google_event_shadow WHERE user_id = :userId")
    suspend fun getAll(userId: String): List<GoogleEventShadowEntity>

    @Query("SELECT * FROM google_event_shadow WHERE user_id = :userId AND task_id IS NOT NULL")
    fun observeMapped(userId: String): Flow<List<GoogleEventShadowEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(shadow: GoogleEventShadowEntity)

    @Query("DELETE FROM google_event_shadow WHERE user_id = :userId AND event_id = :eventId")
    suspend fun delete(userId: String, eventId: String)

    /**
     * Removes shadows for events that are no longer live.
     *
     * Scoped to [userId] for the same reason `deleteStale` is elsewhere: a query that
     * omitted it would delete another profile's mappings.
     */
    @Query(
        "DELETE FROM google_event_shadow WHERE user_id = :userId " +
            "AND event_id NOT IN (:liveEventIds)",
    )
    suspend fun deleteNotIn(userId: String, liveEventIds: List<String>)

    @Query("DELETE FROM google_event_shadow WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String)
}

/** Reads and writes [CalendarImportEventEntity]. */
@Dao
interface CalendarImportEventDao {

    @Query(
        "SELECT * FROM calendar_import_event WHERE user_id = :userId " +
            "AND task_id IS NULL ORDER BY starts_at",
    )
    fun observeUnconverted(userId: String): Flow<List<CalendarImportEventEntity>>

    @Query("SELECT * FROM calendar_import_event WHERE user_id = :userId")
    suspend fun getAll(userId: String): List<CalendarImportEventEntity>

    @Query("SELECT * FROM calendar_import_event WHERE user_id = :userId AND event_id = :eventId")
    suspend fun get(userId: String, eventId: String): CalendarImportEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: CalendarImportEventEntity)

    @Query("DELETE FROM calendar_import_event WHERE user_id = :userId AND event_id = :eventId")
    suspend fun delete(userId: String, eventId: String)

    @Query(
        "UPDATE calendar_import_event SET task_id = :taskId " +
            "WHERE user_id = :userId AND event_id = :eventId",
    )
    suspend fun linkTask(userId: String, eventId: String, taskId: String)

    /**
     * Drops events outside the import window.
     *
     * Scoped to [userId], and the reason the composite key exists: two profiles can hold
     * the same Google event id, and a cleanup that ignored [userId] would delete the other
     * profile's row.
     */
    @Query(
        "DELETE FROM calendar_import_event WHERE user_id = :userId AND starts_at < :beforeMs",
    )
    suspend fun deleteOlderThan(userId: String, beforeMs: Long)

    @Query("DELETE FROM calendar_import_event WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String)
}
