package com.singularity.todo.core.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Batch push request — sent to /sync Edge Function.
 * Matches Flutter sync_core BatchPushRequest wire format.
 */
@Serializable
data class BatchPushRequest(val protocolVersion: Int = 1, val deviceId: String, val patches: List<DeltaPatch>)

/**
 * Batch push response from server.
 */
@Serializable
data class BatchPushResponse(val results: List<PatchResult>)

/**
 * A delta patch representing a single entity change.
 * Matches Flutter sync_core DeltaPatch.
 */
@Serializable
data class DeltaPatch(
    val patchId: String,
    val entityId: String,
    val entityType: DocType,
    val baseVersion: Long,
    val protocolVersion: Int = 1,
    val isDelete: Boolean = false,
    val shadowChecksum: String? = null,
    val ops: List<FieldChange> = emptyList(),
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
)

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
