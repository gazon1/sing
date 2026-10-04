package com.singularity.todo.core.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Batch push request — sent to the `sync_batch_apply` RPC.
 *
 * [profileId] names which of the account's profiles this cycle is for, and the
 * server requires it on every patch. It is a routing dimension *within* the
 * authenticated account, not an authorisation input: the owner comes from the
 * session, and no value here can widen that. It lives on the request rather than
 * on [DeltaPatch] because one push is one profile's cycle, and putting it on the
 * patch would let a patch outlive the profile that built it.
 */
@Serializable
data class BatchPushRequest(
    val protocolVersion: Int = 1,
    val deviceId: String,
    val profileId: String,
    val patches: List<DeltaPatch>,
)

/**
 * Batch push response from server.
 */
@Serializable
data class BatchPushResponse(val results: List<PatchResult>)

/**
 * A delta patch representing a single entity change.
 *
 * Carries field operations and a logical clock, not a snapshot and a row checksum.
 * The checksum is gone: it existed so the server could reject a patch whose base had
 * moved, which resolves a whole-row conflict by arrival order. Per-field merge
 * (REQ-OS-003) needs the patch to say which fields changed, and a checksum says the
 * opposite — that the row as a whole did.
 */
@Serializable
data class DeltaPatch(
    val patchId: String,
    val entityId: String,
    val entityType: DocType,
    val baseVersion: Long,
    val protocolVersion: Int = 1,
    val isDelete: Boolean = false,
    val ops: List<FieldChange> = emptyList(),
    /** Hybrid logical clock the server orders this patch by. */
    val hlc: Hlc? = null,
    val timestampMs: Long? = null,
)

/**
 * A single field change within a DeltaPatch.
 */
@Serializable
data class FieldChange(val field: String, val op: FieldOp, val value: JsonElement? = null)

/**
 * Field operation types.
 */
@Serializable
enum class FieldOp {
    @SerialName("set")
    SET,

    @SerialName("unset")
    UNSET,

    @SerialName("append")
    APPEND,

    @SerialName("remove")
    REMOVE,
}

/**
 * Result of a single patch operation.
 */
@Serializable
data class PatchResult(
    val patchId: String,
    val ok: Boolean,
    val cached: Boolean = false,
    val conflict: Boolean = false,
    val newVersion: Long? = null,
    val newState: JsonElement? = null,
    val serverState: JsonElement? = null,
    val error: String? = null,
) {
    /** Whether this error can be retried. */
    val isRetriable: Boolean get() = !ok && error != "shadow_mismatch" && error != "too_old"
}

/**
 * Sync event received from server during pull.
 */
@Serializable
data class SyncEvent(
    val serverLsn: Long,
    val entityId: String,
    val entityType: DocType,
    val eventType: SyncEventType,
    val data: JsonElement? = null,
    val createdAt: Long,
    /** Protocol version of this event. Events from a newer protocol are skipped. */
    val protocolVersion: Int = 1,
    /**
     * The local profile this event belongs to.
     *
     * Empty means the event predates the profile dimension — an older server, or an
     * older build's row — and such an event applies to whichever scope is pulling.
     * That default is what keeps an old server usable, and it is safe in one direction
     * only: an event that names a profile is never applied to a different one, so the
     * worst a stale server causes is a missing profile dimension, not another
     * profile's data.
     */
    val profileId: String = "",
) {
    /** Whether this event belongs to [scope]'s profile. */
    fun belongsTo(scope: SyncScope): Boolean = profileId.isEmpty() || profileId == scope.profileId
}

object SyncProtocol {
    /** Current protocol version. Events with protocolVersion > CURRENT are dropped. */
    const val CURRENT_PROTOCOL_VERSION = 1
}

/**
 * Type of sync event.
 */
@Serializable
enum class SyncEventType {
    @SerialName("created")
    CREATED,

    @SerialName("updated")
    UPDATED,

    @SerialName("deleted")
    DELETED,

    @SerialName("restored")
    RESTORED,
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Builds a DeltaPatch that sets a single field.
 */
fun deltaPatchSet(
    patchId: String,
    entityId: String,
    entityType: DocType,
    baseVersion: Long,
    field: String,
    value: JsonElement,
): DeltaPatch = DeltaPatch(
    patchId = patchId,
    entityId = entityId,
    entityType = entityType,
    baseVersion = baseVersion,
    ops = listOf(FieldChange(field, FieldOp.SET, value)),
)

/**
 * Builds a delete DeltaPatch.
 */
fun deltaPatchDelete(patchId: String, entityId: String, entityType: DocType, baseVersion: Long): DeltaPatch =
    DeltaPatch(
        patchId = patchId,
        entityId = entityId,
        entityType = entityType,
        baseVersion = baseVersion,
        isDelete = true,
    )
