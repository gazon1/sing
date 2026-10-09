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
    /**
     * The server kept a newer value for every field this patch carried.
     *
     * ## Why this arrives with `ok: true`
     *
     * Nothing went wrong. The server accepted the request, understood it, and applied
     * its own merge — per-field LWW, in which this patch's fields all lost to values
     * already held. `sync_batch_apply` reports it as `ok: true` plus this flag, and
     * verified against the live project on 2026-10-05: two writes to one field, the
     * second clock behind, answer `ok: true, lost: true` with `applied: 0, created: 0`.
     *
     * That combination is what made the signal invisible. `ok` alone said "landed", the
     * engine deleted the outbox row and settled the shadow as confirmed, and the
     * device promoted a state the server never took — after which the next diff was
     * computed against that fiction and the edit was never re-sent. `StableJson` sets
     * `ignoreUnknownKeys = true`, so before this field existed the answer was dropped
     * at the parse boundary without a word.
     *
     * See REQ-OS-026 and #203. Note the neighbouring [serverState] is **not** a
     * shortcut out of this: the server never populates it, which is why the resolution
     * reads the shadow's own confirmed state instead.
     */
    val lost: Boolean = false,
) {
    /**
     * Whether this result should be retried.
     *
     * ## The codes that must not be retried
     *
     * A retry re-sends the same patch to the same server, so it can only help when the
     * *server* would give a different answer next time. Three conditions satisfy that
     * and are terminal:
     *
     * - `shadow_mismatch` — the server holds a different state than the patch expected.
     *   Waiting does not make the two converge; only a fresh diff does, which is what the
     *   shadow does on the next local edit.
     * - `too_old` — the patch's clock is behind one the server already has. The same
     *   reason: a newer edit won, and re-sending the older one changes nothing.
     * - `not_found` — the row the patch targets is not there. It does not appear by
     *   waiting.
     * - `too_large` — the patch exceeds a server limit. Its size does not shrink by
     *   waiting, and the content is the user's, so retrying cannot succeed.
     *
     * ## Why an unknown code is still retried
     *
     * An unrecognised code is treated as transient, and that is a choice rather than an
     * omission. Retrying a permanent error costs a retry budget and ends in the dead
     * letter store, where a human can see it and the change is not lost. Dropping a
     * transient error instead loses the user's edit with no trace at all. So the
     * asymmetry is deliberate: the cost of being wrong is a delayed failure, not a
     * silent loss.
     *
     * What that makes necessary is that a *known* permanent code is in the set above
     * rather than merely absent from a denylist — and `SyncProtocolTest` asserts the
     * classification of every code the server can return, so a new one is added
     * deliberately instead of inheriting "retry forever".
     */
    val isRetriable: Boolean get() = !ok && error !in SyncProtocol.TERMINAL_ERRORS
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
    const val CURRENT_PROTOCOL_VERSION = SyncContract.CURRENT_PROTOCOL_VERSION

    // ── The server's error vocabulary ────────────────────────────────────────
    //
    // Kept as named constants because three places need to agree on the spelling: the
    // classifier below, the tests that pin it, and the SQL that produces it. A string
    // literal repeated in each of those is how one of them drifts.

    /** The server holds a different state than this patch expected. */
    const val SHADOW_MISMATCH = "shadow_mismatch"

    /** The patch's clock is behind one the server already has. */
    const val TOO_OLD = "too_old"

    /** The row the patch targets is not there. */
    const val NOT_FOUND = "not_found"

    /** The patch exceeds a server limit. */
    const val TOO_LARGE = "too_large"

    /** A field the server will not accept a value for. */
    const val FIELD_NOT_WRITABLE = "field_not_writable"

    /** The row exists but is not this owner's to change. */
    const val ROW_UNAVAILABLE = "row_unavailable"

    /**
     * Codes a retry cannot fix.
     *
     * A retry re-sends the same patch to the same server, so it can only help when the
     * server would answer differently next time. Each of these says the answer is a
     * property of the request rather than of the moment: the state moved, the clock was
     * behind, the row is not there, the payload is too big, or the server is refusing
     * deliberately. None improves by waiting.
     *
     * An unrecognised code is **not** in here, and that is a choice. Retrying an error
     * that turns out to be permanent costs a retry budget and ends in the dead letter
     * store, where it is visible and the change is not lost. Dropping a transient one
     * loses the user's edit with no trace. So the asymmetry is deliberate, and the
     * obligation it creates is that a code which becomes permanently unfixable has to be
     * added here deliberately — which `SyncProtocolTest` pins over the whole table.
     */
    val TERMINAL_ERRORS: Set<String> = setOf(
        SHADOW_MISMATCH,
        TOO_OLD,
        NOT_FOUND,
        TOO_LARGE,
        FIELD_NOT_WRITABLE,
        ROW_UNAVAILABLE,
    )
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
