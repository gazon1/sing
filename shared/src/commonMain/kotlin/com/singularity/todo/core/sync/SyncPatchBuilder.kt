package com.singularity.todo.core.sync

import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.serialization.StableJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Turns a local entity into a [DeltaPatch] carrying only the fields that changed.
 *
 * The previous version sent a full snapshot plus a row checksum, and the checksum
 * existed only so the server could reject a patch whose base no longer matched —
 * a whole-row conflict, resolved by whoever arrived last. That is a coarser
 * contract than the product wants: two people editing different fields of the same
 * task cannot both win, and REQ-OS-003 says they must.
 *
 * A diff needs a base, and the base is [SyncShadowEntity]. See it for why the
 * shadow has two columns.
 */
internal class SyncPatchBuilder(
    private val shadowDao: SyncShadowDao,
    private val hlcFactory: HlcFactory,
    private val idGenerator: IdGenerator,
) {
    private val json = StableJson

    /**
     * Builds the patch for [entity] and marks [scope] as having it in flight.
     *
     * The in-flight marker is written here rather than after the push, because the
     * next edit to the same entity has to diff against *this* state to avoid
     * re-sending these fields. It is cleared again on success (promoted to confirmed)
     * or on abandonment (released).
     */
    suspend fun build(entity: SyncableEntity, scope: SyncScope, nowMillis: Long): DeltaPatch {
        val state = entity.toJson()
        val encoded = json.encodeToString(JsonObject.serializer(), state)
        val typeKey = entity.docType.key
        val shadow = shadowDao.get(scope.ownerId, scope.profileId, typeKey, entity.syncId)

        // Base is the in-flight state when there is one: the server does not have
        // the confirmed state plus this patch's fields yet, it will have them once
        // the patch lands, and a diff against the older state would re-send them.
        // No shadow at all means the server has never been told about this entity, so
        // the base is the empty object and the first patch carries every field.
        val base = decode(shadow?.inFlightJson ?: shadow?.confirmedJson ?: EMPTY_STATE)
        val ops = diff(base, state)

        val patch = DeltaPatch(
            patchId = idGenerator.next(),
            entityId = entity.syncId,
            entityType = entity.docType,
            // From the shadow, not from the entity. The entity's own copy is written
            // by the local repositories, which have no idea what the server said, so it
            // stayed 0 forever and every patch claimed the server had never seen the
            // row. The shadow is where the engine records what the server confirmed.
            baseVersion = shadow?.serverVersion ?: 0,
            isDelete = false,
            ops = ops,
            // The clock the server orders by. Taken from the factory rather than from
            // the wall clock, so two patches authored in the same millisecond still
            // have a total order — and so a patch authored on a device whose clock is
            // minutes behind wins against a field the ahead device never touched,
            // while losing against one it touched later. That is the hybrid part.
            hlc = hlcFactory.tick(),
            timestampMs = nowMillis,
        )

        shadowDao.upsert(
            SyncShadowEntity(
                ownerId = scope.ownerId,
                profileId = scope.profileId,
                entityType = typeKey,
                entityId = entity.syncId,
                // NOT the local state. An entity with no shadow has never been
                // uploaded, so the server holds nothing; seeding `confirmed_json`
                // with the local state would claim the opposite, and the next edit
                // would then be diffed against a state the server does not have and
                // would send only the changed field — silently uploading a task with
                // no title.
                confirmedJson = shadow?.confirmedJson ?: EMPTY_STATE,
                inFlightJson = encoded,
                inFlightPatchId = patch.patchId,
                // Carried, not reset. This is a whole-row replace, so a new row built
                // here would default the version to 0 and the *next* patch would claim
                // the server had never seen the row — reintroducing, one step later,
                // exactly the defect the column exists to fix.
                serverVersion = shadow?.serverVersion ?: 0,
            ),
        )
        return patch
    }

    private fun decode(encoded: String): JsonObject =
        json.decodeFromString(JsonObject.serializer(), encoded)

    /**
     * Field-level difference between the base the server will have and the local state.
     *
     * The diff is symmetric — a field that disappeared is an `unset`, not an
     * omission. Sending only the changed fields and leaving removals implicit would
     * make a cleared field indistinguishable from one the client never knew about.
     */
    internal fun diff(base: JsonObject, next: JsonObject): List<FieldChange> {
        val ops = mutableListOf<FieldChange>()

        for ((field, value) in next) {
            if (base[field] != value) {
                ops += change(field, value)
            }
        }
        for (field in base.keys) {
            if (field !in next) {
                ops += FieldChange(field = field, op = FieldOp.UNSET, value = null)
            }
        }
        return ops.sortedBy { it.field }
    }

    /**
     * Builds the operation for one changed field.
     *
     * A null value is [FieldOp.UNSET] and carries no value at all, rather than a
     * `JsonNull`: "unset this field" and "set this field to null" are different
     * instructions, and a server that reads the value cannot tell them apart if the
     * unset also ships one.
     *
     * Arrays are replaced wholesale. Append and remove exist in the protocol for list
     * merging, but no entity here has a list whose order-independent merge is
     * correct, so choosing them would be an invented semantic rather than an
     * implemented one. The field's own clock decides the winner.
     */
    private fun change(field: String, value: JsonElement): FieldChange =
        if (value is JsonNull) {
            FieldChange(field = field, op = FieldOp.UNSET, value = null)
        } else {
            FieldChange(field = field, op = FieldOp.SET, value = value)
        }

    private companion object {
        /** "The server has nothing": the base for an entity that has never synced. */
        const val EMPTY_STATE = "{}"
    }
}
