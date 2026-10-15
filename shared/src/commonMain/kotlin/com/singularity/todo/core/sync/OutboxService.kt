package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.AppError
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
 * A new patch is built against the previous in-flight state, so it carries every field
 * the one it replaces carried — replacing loses nothing. Without this the outbox grows
 * without bound under fast editing, and the shadow's "promote only if this patch still
 * owns the marker" guard could never fire.
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
    suspend fun enqueue(entity: SyncableEntity): Result<Unit> {
        val active =
            phases
                .localStorage("sync.scope.read", "read the active sync scope for enqueue") {
                    scopeProvider.current.first()
                }.getOrElse { return Result.failure(it.toAppError()) }
                ?: return Result.success(Unit)

        val patch = patchBuilder.build(entity, active, monoNow())
        val payload = json.encodeToString(patch)

        // Coalesce per entity before inserting: an entity has at most one patch in flight.
        phases
            .localStorage("sync.outbox.delete", "coalesce the outbox row before insert") {
                outboxDao.deleteByEntity(active.ownerId, entity.syncId)
            }.getOrElse { return Result.failure(it.toAppError()) }

        phases
            .localStorage("sync.outbox.insert", "insert the pending patch") {
                outboxDao.insert(
                    SyncOutboxEntity(
                        patchId = patch.patchId,
                        ownerId = active.ownerId,
                        entityId = entity.syncId,
                        entityType = entity.docType.key,
                        payload = payload,
                        createdAt = patch.timestampMs ?: monoNow(),
                    ),
                )
            }.getOrElse { return Result.failure(it.toAppError()) }

        return Result.success(Unit)
    }
}
