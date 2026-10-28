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
            )
            .asAppError()

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
    ): SyncOutboxEntity {
        val patch = patchBuilder.build(entity, active, monoNow())
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
