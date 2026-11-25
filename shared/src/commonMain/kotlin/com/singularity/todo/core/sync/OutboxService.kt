package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.toAppError
import com.singularity.todo.core.serialization.StableJson
import kotlinx.coroutines.flow.first

/**
 * Owns the outbox write path: coalescing delete + insert of a [SyncOutboxEntity].
 *
 * Extracted from [SyncEngine.enqueue] so the outbox write path is independently
 * testable — particularly the coalescing behaviour that [enqueue]'s 18 call sites
 * depend on without knowing it.
 *
 * ## Coalescing
 *
 * Inserts are preceded by a delete of any existing row for the same `(ownerId, entityId)`.
 * Without this the outbox grows without bound under fast editing, and the shadow's
 * "promote only if this patch still owns the marker" guard could never fire.
 *
 * Because the replaced row is deleted rather than merged into, the new patch has to
 * carry everything the old one did. That is why the patch is built against the
 * confirmed state — what the server really holds — and not against the in-flight
 * one: diffing against the in-flight state would leave the fields only the replaced
 * patch carried with the row that is being deleted.
 */
internal class OutboxService(
    private val outboxDao: SyncOutboxDao,
    private val patchBuilder: SyncPatchBuilder,
    private val scopeProvider: SyncScopeProvider,
    private val engineState: SyncEngineState,
    private val monoNow: () -> Long,
    private val log: Logger = Logger.withTag("OutboxService"),
) {
    private val phases: SyncPhaseReporter get() = engineState.phases
    private val json = StableJson

    /**
     * Builds a patch for [entity] and inserts it into the outbox, coalescing any
     * existing patch for the same entity first.
     *
     * Returns `Result.success(Unit)` if the patch was queued, `Result.failure` if
     * the outbox write or the scope read failed.
     *
     * Eighteen repository call sites discard the returned `Result`, so reporting
     * belongs here rather than in each of them.
     *
     * @see REQ-OS-028
     */
    suspend fun enqueue(entity: SyncableEntity): Result<Unit> =
        readActiveScope()
            .map { active -> active?.let { insertCoalesced(entity, it) } ?: Result.success(Unit) }
            .fold(
                onSuccess = { it },
                onFailure = { Result.failure(it) },
            ).asAppError()

    /**
     * The scope the write is attributed to, or `null` when the device has no active
     * scope — a signed-out device has nothing to push for, so this is not an error.
     */
    private suspend fun readActiveScope(): Result<SyncScope?> =
        phases
            .localStorage("sync.scope.read", "read the active sync scope for enqueue") {
                scopeProvider.current.first()
            }

    /**
     * Queues many entities in one go — the batched form of [enqueue].
     *
     * ## What it saves
     *
     * The per-entity path costs, for every entity: a scope read, a coalescing delete,
     * a patch build (which mints a clock and writes it to the DataStore), an outbox
     * insert and a shadow write. A profile wipe of ten thousand rows paid all of it
     * ten thousand times, and the DataStore write — a file write per patch — is what
     * made the wipe take minutes.
     *
     * Here the scope is read once, the clocks are minted in one batch (one clock
     * write for the whole batch), and the patches are built, queued and released
     * chunk by chunk — a chunk of [SQL_BIND_CHUNK] rows, one shadow statement, one
     * coalescing delete and one insert each.
     *
     * ## What it does not change
     *
     * The contract is the same one [enqueue] keeps: one patch per entity, each with
     * its own clock, each replacing the entity's previous queued patch, each
     * carrying the entity's own state. Nothing is dropped and nothing is summarised:
     * the server still learns about every removed row.
     */
    suspend fun enqueueAll(entities: List<SyncableEntity>): Result<Unit> {
        if (entities.isEmpty()) return Result.success(Unit)
        return readActiveScope()
            .map { active -> active?.let { insertAllCoalesced(entities, it) } ?: Result.success(Unit) }
            .fold(
                onSuccess = { it },
                onFailure = { Result.failure(it) },
            ).asAppError()
    }

    /**
     * The coalesce-then-insert pair for a whole batch.
     *
     * The order is the same as the single-entity path's: the entity's previous
     * queued patch goes first, so at most one patch per entity is in flight.
     *
     * The work happens chunk by chunk — the patches of a chunk are built, queued
     * and released before the next chunk is built. A batch that held every patch
     * at once would not fit in a phone's heap once the profile holds tens of
     * thousands of rows.
     */
    private suspend fun insertAllCoalesced(
        entities: List<SyncableEntity>,
        active: SyncScope,
    ): Result<Unit> =
        phases
            .localStorage("sync.outbox.write", "queue the pending patches") {
                val nowMillis = monoNow()
                patchBuilder.forEachChunk(entities, active, nowMillis) { chunk, patches ->
                    val rows =
                        chunk.mapIndexed { index, entity ->
                            pendingRow(entity, patches[index], active)
                        }
                    chunk
                        .map { it.syncId }
                        .distinct()
                        .inBindableChunks()
                        .forEach { ids ->
                            outboxDao.deleteByEntities(active.ownerId, active.profileId, ids)
                        }
                    outboxDao.insertAll(rows)
                }
            }.map { }

    /**
     * The coalesce-then-insert pair. Split from [enqueue] because the delete must not
     * run when there is no active scope, and a single `flatMap` over two phases would
     * hide that ordering behind a chain of guards.
     */
    private suspend fun insertCoalesced(
        entity: SyncableEntity,
        active: SyncScope,
    ): Result<Unit> =
        deleteCoalesceRow(active, entity).fold(
            onSuccess = { insertRow(entity, active) },
            onFailure = { Result.failure(it) },
        )

    /** Coalesce per entity before inserting: an entity has at most one patch in flight. */
    private suspend fun deleteCoalesceRow(
        active: SyncScope,
        entity: SyncableEntity,
    ): Result<Unit> =
        phases.localStorage("sync.outbox.delete", "coalesce the outbox row before insert") {
            outboxDao.deleteByEntity(active.ownerId, active.profileId, entity.syncId)
        }

    private suspend fun insertRow(
        entity: SyncableEntity,
        active: SyncScope,
    ): Result<Unit> =
        phases.localStorage("sync.outbox.insert", "insert the pending patch") {
            outboxDao.insert(pendingRow(entity, active))
        }

    /** The row this entity becomes, built against the current state rather than reused. */
    private suspend fun pendingRow(
        entity: SyncableEntity,
        active: SyncScope,
    ): SyncOutboxEntity = pendingRow(entity, patchBuilder.build(entity, active, monoNow()), active)

    /** The row a built patch becomes. */
    private fun pendingRow(
        entity: SyncableEntity,
        patch: DeltaPatch,
        active: SyncScope,
    ): SyncOutboxEntity {
        val payload = json.encodeToString(patch)
        return SyncOutboxEntity(
            patchId = patch.patchId,
            ownerId = active.ownerId,
            profileId = active.profileId,
            entityId = entity.syncId,
            entityType = entity.docType.key,
            payload = payload,
            createdAt = patch.timestampMs ?: monoNow(),
        )
    }

    /** The phase reporter already returns [com.singularity.todo.core.error.AppError]; this
     *  normalises anything else that arrives from a future phase implementation. */
    private fun <T> Result<T>.asAppError(): Result<T> =
        fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it.toAppError()) },
        )
}
