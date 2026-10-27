package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.auth.accountIdOrNull
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.error.toAppError
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.serialization.StableJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonObject

/**
 * Push phase of the sync cycle.
 *
 * Sends pending patches to the server, reconciles the outbox and shadow store with the
 * server response, and reports outcomes via [SyncPhaseReporter].
 *
 * ## D1 — Superseded outcome
 *
 * When the server confirms a patch (`ok: true`) but the local outbox no longer holds
 * a row for it (because a later local edit coalesced it away before the response
 * arrived), the patch is **superseded** — not failed. `result.patchId` lookup in the
 * plan snapshot yields `null`, which is the signal.
 *
 * ## D2 — Captured scope for dead-letter
 *
 * `deferOrDeadLetter` receives the `active` scope from the plan rather than re-reading
 * `scopeProvider.current`. A profile switch between the response and this call would
 * otherwise file the dead-letter under the wrong owner.
 */
const val PUSH_BATCH_SIZE = 100

internal class PushPhase(
    private val api: SyncApiClient,
    private val authRepository: AuthRepository,
    private val outboxDao: SyncOutboxDao,
    private val deadLetterDao: SyncDeadLetterDao,
    private val shadowDao: SyncShadowDao,
    private val idGenerator: IdGenerator,
    private val scopeProvider: SyncScopeProvider,
    private val patchBuilder: SyncPatchBuilder,
    private val monoNow: () -> Long,
    private val phases: SyncPhaseReporter,
    private val writerProvider: () -> SyncDocumentWriter,
    private val retryPolicy: PatchRetryPolicy,
    private val scope: AutoCloseableCoroutineScope,
) {
    private val log = Logger.withTag("PushPhase")

    /** One reading of the monotonic clock. */
    private fun now(): Long = monoNow()

    /**
     * Pushes all pending patches to the server.
     */
    internal suspend fun push(): PhaseResult<PushSummary> {
        val session = authRepository.currentSession.value
        val signedIn = session is Session.SignedIn
        if (signedIn) phases.setPushStatus(isPushing = true)

        if (!signedIn) {
            phases.setPushStatus(isPushing = false)
            return PhaseResult.Ok(PushSummary(0, 0, 0))
        }

        var succeeded = 0
        var failed = 0
        var lost = 0
        var superseded = 0
        var discarded = 0
        var processed = 0

        while (true) {
            val plan = planPush().getOrElse { error ->
                phases.pushFailed(error.toAppError(), 0)
                phases.setPushStatus(isPushing = false)
                return PhaseResult.Failed(error.toAppError())
            }
            val ready = (plan as? PushPlan.Ready) ?: break

            val result = pushWithPlan(ready, session)
            when (result) {
                is PhaseResult.Ok -> {
                    val s = result.getOrThrow()
                    succeeded += s.succeeded
                    failed += s.failed
                    lost += s.lost
                    superseded += s.superseded
                    discarded += s.discarded
                    processed += s.processed
                }

                is PhaseResult.Failed -> {
                    phases.setPushStatus(isPushing = false)
                    return result
                }

                else -> {}
            }
        }

        val summary = PushSummary(
            processed = processed,
            succeeded = succeeded,
            failed = failed,
            lost = lost,
            superseded = superseded,
            discarded = discarded,
        )
        phases.recordPushResult(Result.success(summary))
        phases.setPushStatus(isPushing = false)
        return PhaseResult.Ok(summary)
    }

    /**
     * Processes the push plan produced by [planPush].
     *
     * The plan's [PushPlan.Ready.scope] is used as the authoritative owner of all
     * patches in this plan — it is captured at plan-building time and used throughout
     * response processing rather than re-reading the current scope.
     */
    private suspend fun pushWithPlan(ready: PushPlan.Ready, session: Session): PhaseResult<PushSummary> {
        val pending = ready.pending
        val activeScope = ready.scope

        return try {
            val response = api.batchPush(ready.request)
            val counts = processResponses(response, pending, activeScope, session)
            PhaseResult.Ok(counts.toSummary())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val error = e.toAppError()
            phases.pushFailed(error, pending.size)
            PhaseResult.Failed(error)
        }
    }

    /**
     * Handles the server's push response, updating the outbox and shadow store.
     * Returns the counts needed to build a [PushSummary].
     *
     * D1 (superseded): a `patch == null` lookup means the outbox row was removed
     * between plan-time and now — a local edit coalesced it. The server confirmed
     * `ok: true` but this device no longer has the patch.
     *
     * D2 (dead-letter scope): [activeScope] is the plan's scope, captured at
     * plan-building time. A re-read here would file dead-letters under the wrong owner.
     */
    private suspend fun processResponses(
        response: BatchPushResponse,
        pending: List<SyncOutboxEntity>,
        activeScope: SyncScope,
        session: Session,
    ): PushCounts {
        // REQ-UA-018: is the account that authorised this request still the one
        // signed in? Compared by account, not by session.
        val requestedBy = session.accountIdOrNull
        val nowSignedInAs = authRepository.currentSession.value.accountIdOrNull

        if (requestedBy != nowSignedInAs) {
            log.e {
                "Push response discarded: requested under [$requestedBy], " +
                    "now [$nowSignedInAs]. ${pending.size} row(s) stay queued."
            }
            phases.recordPushResult(Result.success(PushSummary(0, 0, 0, discarded = response.results.size)))
            return PushCounts(succeeded = 0, failed = 0, lost = 0, superseded = 0, discarded = response.results.size)
        }

        // onBeforeResponseLoop: the seam for in-flight mutations. A local edit that
        // coalesced a pending row fires here — after the server confirmed the patch but
        // before this device processes the response. The D1 race.
        val mPending = pending.toMutableList()
        api.onBeforeResponseLoop(mPending, response)

        val patchesById = mPending
            .associate { it.patchId to StableJson.decodeFromString<DeltaPatch>(it.payload) }

        var succeeded = 0
        var failed = 0
        var lost = 0
        var superseded = 0

        response.results.forEach { result ->
            val patch = patchesById[result.patchId]

            when {
                // D1 — superseded: server confirmed the patch but the outbox row is gone.
                patch == null -> superseded++

                // `lost` is checked before `ok` because the server reports a lost race
                // as `ok: true`. Branching on `ok` first is the original defect #203.
                result.ok && result.lost -> {
                    outboxDao.delete(result.patchId)
                    resolveLostRace(patch, scope = activeScope)
                    lost++
                }

                result.ok -> {
                    outboxDao.delete(result.patchId)
                    settleShadow(patch, applied = true, scope = activeScope, serverVersion = result.newVersion)
                    succeeded++
                }

                result.isRetriable -> {
                    // The row is NOT deleted here. A retriable failure defers the
                    // patch: `deferOrDeadLetter` updates it in place (attempts + 1,
                    // nextAttemptAt) or, once the attempts are exhausted, moves it to
                    // the dead-letter store and deletes it *there*.
                    //
                    // Deleting it here first made that update a no-op — `markFailed` is
                    // an UPDATE, not an upsert — so the patch vanished from the outbox
                    // with no retry and no dead letter. A user's edit was destroyed by
                    // a server that merely said "busy".
                    deferOrDeadLetter(pending.firstOrNull { row -> row.patchId == result.patchId }, result, activeScope)
                    failed++
                }

                else -> {
                    outboxDao.delete(result.patchId)
                    settleShadow(patch, applied = false, scope = activeScope)
                    failed++
                }
            }
        }

        return PushCounts(succeeded, failed, lost, superseded, discarded = 0)
    }

    private data class PushCounts(
        val succeeded: Int,
        val failed: Int,
        val lost: Int,
        val superseded: Int,
        val discarded: Int,
    ) {
        /**
         * The summary of these counts.
         *
         * [PushSummary.processed] is the number of results this client acted upon,
         * so it is the sum of the four acted-upon buckets — **not** the number of
         * results the server sent. A discarded result is in neither: the server
         * answered, and the device declined to act on the answer.
         */
        fun toSummary() = PushSummary(
            processed = succeeded + failed + lost + superseded,
            succeeded = succeeded,
            failed = failed,
            lost = lost,
            superseded = superseded,
            discarded = discarded,
        )
    }

    /**
     * Assembles what to send, or reports why it could not be assembled.
     */
    private suspend fun planPush(): Result<PushPlan?> {
        val nowMillis = now()

        // Read the scope FIRST — it is captured and used throughout response processing.
        // Re-reading would let a profile switch put this cycle's patches under one
        // profile and their shadow under another.
        val activeScope =
            phases
                .localStorage("sync.scope.read", "read the active sync scope") {
                    scopeProvider.current.first()
                }.getOrElse { error ->
                    phases.pushFailed(error.toAppError(), 0)
                    return Result.failure(error)
                }

        val pending =
            activeScope
                ?.let { s ->
                    phases.localStorage("sync.outbox.read", "read the pending changes") {
                        outboxDao.getPending(nowMillis, s.ownerId)
                    }
                }?.getOrElse { error ->
                    phases.pushFailed(error.toAppError(), 0)
                    return Result.failure(error)
                }
                ?: emptyList()

        val batched = pending.take(PUSH_BATCH_SIZE)
        if (batched.isEmpty()) return Result.success(null)

        return phases
            .localStorage("sync.outbox.decode", "assemble the queued changes") {
                val patches = batched.map { entity -> StableJson.decodeFromString<DeltaPatch>(entity.payload) }
                PushPlan.Ready(
                    scope = activeScope!!,
                    pending = batched,
                    patches = patches,
                    request =
                        BatchPushRequest(
                            deviceId = idGenerator.next(),
                            profileId = activeScope.profileId,
                            patches = patches,
                        ),
                )
            }.fold(
                onSuccess = { Result.success(it as PushPlan?) },
                onFailure = { error ->
                    phases.pushFailed(error.toAppError(), batched.size)
                    Result.failure(error.toAppError())
                },
            )
    }

    /**
     * Backs a rejected patch off, or gives up on it.
     *
     * D2 fix: receives the [activeScope] scope as a parameter rather than re-reading
     * `scopeProvider.current`. The scope the patch was built under is the scope it
     * belongs to — a different scope here would file the dead-letter under the wrong owner.
     *
     * @param entity  the outbox row. May be null if it was already removed (superseded).
     * @param result  the server's decision on this patch.
     * @param activeScope  the sync scope captured at plan-building time.
     */
    private suspend fun deferOrDeadLetter(
        entity: SyncOutboxEntity?,
        result: PatchResult,
        activeScope: SyncScope,
    ) {
        if (entity == null) return

        val attempts = (outboxDao.attemptsOf(entity.patchId) ?: entity.attempts) + 1
        val reason = result.error ?: "Unknown error"

        if (retryPolicy.isExhausted(attempts)) {
            // The patch is set aside, not destroyed. Its in-flight shadow marker is
            // released so the confirmed state stays at what the server really has.
            StableJson.decodeFromString<DeltaPatch>(entity.payload).let { patch ->
                shadowDao.release(
                    ownerId = activeScope.ownerId,
                    profileId = activeScope.profileId,
                    entityType = patch.entityType.key,
                    entityId = patch.entityId,
                    patchId = patch.patchId,
                )
            }
            deadLetterDao.insert(
                SyncDeadLetterEntity(
                    patchId = entity.patchId,
                    // Carried from the row being shelved, not re-read from scope:
                    // the patch is set aside because of what happened to it, so it
                    // stays that patch's. A profile that moved in between must not
                    // file it under the wrong account.
                    ownerId = entity.ownerId,
                    entityId = entity.entityId,
                    entityType = entity.entityType,
                    payload = entity.payload,
                    createdAt = entity.createdAt,
                    failedAt = now(),
                    attempts = attempts,
                    lastError = reason,
                ),
            )
            outboxDao.delete(entity.patchId)
            log.e {
                "Patch ${entity.patchId} failed $attempts times; moved to the dead letter " +
                    "store [error=$reason]"
            }
            return
        }

        val delay = retryPolicy.delayFor(attempts)
        outboxDao.markFailed(
            id = entity.patchId,
            error = reason,
            nextAttemptAt = now() + delay,
        )
        log.w { "Patch ${entity.patchId} failed (attempt $attempts); retrying in ${delay}ms" }
    }

    /**
     * Returns the row to the state the server holds, and drops the marker that would
     * otherwise re-send the edit that lost.
     *
     * ## Where the server's state comes from
     *
     * `PatchResult.serverState` is never populated by the server. The state this
     * device already has is the shadow's `confirmedJson` — the server's last known state.
     */
    private suspend fun resolveLostRace(
        patch: DeltaPatch,
        scope: SyncScope,
    ) {
        val typeKey = patch.entityType.key
        val confirmed =
            shadowDao
                .get(scope.ownerId, scope.profileId, typeKey, patch.entityId)
                ?.confirmedJson

        val reverted =
            confirmed?.let { document ->
                runCatchingCancellable {
                    val json = StableJson.parseToJsonElement(document).jsonObject
                    writerProvider().upsert(patch.entityType, json)
                }.onFailure { e ->
                    log.e(e) {
                        "Patch ${patch.patchId} lost its race and the row could not be " +
                            "returned to the server's state; the edit is left un-sent"
                    }
                }.isSuccess
            } ?: true

        if (reverted) {
            settleShadow(patch, applied = false, scope = scope)
        }
    }

    private suspend fun settleShadow(
        patch: DeltaPatch,
        applied: Boolean,
        scope: SyncScope,
        serverVersion: Long? = null,
    ) {
        val typeKey = patch.entityType.key
        if (applied) {
            shadowDao.confirm(
                ownerId = scope.ownerId,
                profileId = scope.profileId,
                entityType = typeKey,
                entityId = patch.entityId,
                patchId = patch.patchId,
                json =
                    shadowDao
                        .get(scope.ownerId, scope.profileId, typeKey, patch.entityId)
                        ?.inFlightJson ?: return,
                serverVersion = serverVersion,
            )
        } else {
            shadowDao.release(
                ownerId = scope.ownerId,
                profileId = scope.profileId,
                entityType = typeKey,
                entityId = patch.entityId,
                patchId = patch.patchId,
            )
        }
    }
}

/** Sealed family of [PushPlan] outcomes. */
private sealed class PushPlan {
    /**
     * A plan with patches to send.
     *
     * The [scope] is non-null and was validated at plan-building time. It is the
     * authoritative owner of all patches in this plan and is used throughout response
     * processing rather than re-reading the current scope.
     */
    data class Ready(
        val scope: SyncScope,
        val pending: List<SyncOutboxEntity>,
        val patches: List<DeltaPatch>,
        val request: BatchPushRequest,
    ) : PushPlan() {
        init {
            require(scope.profileId.isNotBlank()) {
                "PushPlan.Ready requires a non-blank profileId"
            }
        }
    }

    /** Nothing to push — the queue is empty. */
    data object Empty : PushPlan()
}
