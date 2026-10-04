package com.singularity.todo.core.sync

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/**
 * What the server is known to hold for one entity, per scope.
 *
 * ## Why the client has to remember this
 *
 * To send a diff rather than a snapshot, the client needs the state the diff should
 * be taken against — and after a push the server's state is the client's only copy
 * of it. Sending the whole entity on every edit works without any of this, which is
 * why it is tempting: it costs bandwidth, it overwrites a concurrent edit to a
 * field the user did not touch, and it makes the server's per-field merge
 * (REQ-OS-003) impossible to implement, because the patch no longer says which
 * fields the user actually changed.
 *
 * ## Two columns, not one, and why
 *
 * `confirmed_json` is what the server has. `in_flight_json` is what a queued patch
 * will bring it to. They differ while a patch is in the outbox, and a diff must be
 * taken against `in_flight_json` — diffing against `confirmed_json` would re-send
 * fields an earlier pending patch is already carrying, which is the duplicate-write
 * problem the in-flight column exists to prevent.
 *
 * A single "last uploaded" column cannot express this, and the bug it produces is
 * invisible: every patch is applied correctly, and the payload is quietly twice the
 * size it needs to be for as long as the outbox is non-empty.
 */
@Entity(tableName = "sync_shadow", primaryKeys = ["owner_id", "profile_id", "entity_type", "entity_id"])
data class SyncShadowEntity(
    @ColumnInfo("owner_id") val ownerId: String,
    @ColumnInfo("profile_id") val profileId: String,
    @ColumnInfo("entity_type") val entityType: String,
    @ColumnInfo("entity_id") val entityId: String,
    /** Serialised entity state the server is known to hold. */
    @ColumnInfo("confirmed_json") val confirmedJson: String,
    /** State a queued patch will bring the server to, or null when nothing is queued. */
    @ColumnInfo("in_flight_json") val inFlightJson: String? = null,
    /** The patch that produced [inFlightJson]; the guard on promoting it. */
    @ColumnInfo("in_flight_patch_id") val inFlightPatchId: String? = null,
)

/**
 * DAO for [SyncShadowEntity].
 *
 * The promotion query is guarded on `in_flight_patch_id` rather than on `entity_id`
 * alone. Without the guard, a response arriving for a superseded patch would promote
 * that patch's state over a newer queued one, and the newer patch's fields would
 * never be sent — a silent loss that no counter would show.
 */
@Dao
interface SyncShadowDao {

    @Query(
        "SELECT * FROM sync_shadow WHERE owner_id = :ownerId AND profile_id = :profileId " +
            "AND entity_type = :entityType AND entity_id = :entityId",
    )
    suspend fun get(ownerId: String, profileId: String, entityType: String, entityId: String): SyncShadowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncShadowEntity)

    /**
     * Promotes the in-flight state to confirmed, but only if [patchId] is still the
     * patch that owns the in-flight state.
     *
     * Returns the number of rows changed: 0 means a newer patch superseded this one,
     * and the promotion must not happen.
     */
    @Query(
        "UPDATE sync_shadow SET confirmed_json = :json, in_flight_json = NULL, in_flight_patch_id = NULL " +
            "WHERE owner_id = :ownerId AND profile_id = :profileId AND entity_type = :entityType " +
            "AND entity_id = :entityId AND in_flight_patch_id = :patchId",
    )
    suspend fun confirm(
        ownerId: String,
        profileId: String,
        entityType: String,
        entityId: String,
        patchId: String,
        json: String,
    ): Int

    /**
     * Drops the in-flight marker without advancing the confirmed state.
     *
     * Used when a patch is abandoned — dead-lettered, or rejected as permanently
     * unapplicable. The next local edit then re-diffs from [SyncShadowEntity.confirmedJson]
     * and re-sends the fields the abandoned patch would have carried, which is the
     * whole point of not advancing `confirmed_json` optimistically.
     */
    @Query(
        "UPDATE sync_shadow SET in_flight_json = NULL, in_flight_patch_id = NULL " +
            "WHERE owner_id = :ownerId AND profile_id = :profileId AND entity_type = :entityType " +
            "AND entity_id = :entityId AND in_flight_patch_id = :patchId",
    )
    suspend fun release(
        ownerId: String,
        profileId: String,
        entityType: String,
        entityId: String,
        patchId: String,
    ): Int

    @Query("DELETE FROM sync_shadow WHERE owner_id = :ownerId AND profile_id = :profileId")
    suspend fun clearScope(ownerId: String, profileId: String)

    @Query("DELETE FROM sync_shadow")
    suspend fun clearAll()
}
