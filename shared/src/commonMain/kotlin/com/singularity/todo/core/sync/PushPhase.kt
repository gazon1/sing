package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.auth.accountIdOrNull
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.original
import com.singularity.todo.core.error.toAppError
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.serialization.StableJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock

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
internal class PushPhase(
    private val api: SyncApiClient,
    private val authRepository: AuthRepository,
    private val outboxDao: SyncOutboxDao,
    private val deadLetterDao: SyncDeadLetterDao,
    private val shadowDao: SyncShadowDao,
    private val idGenerator: IdGenerator,
    private val scopeProvider: SyncScopeProvider,
    private val patchBuilder: SyncPatchBuilder,
    private val clock: Clock,
    private val phases: SyncPhaseReporter,
    private val writerProvider: () -> SyncDocumentWriter,
    private val retryPolicy: PatchRetryPolicy,
    private val scope: AutoCloseableCoroutineScope,
) {
    private val log = Logger.withTag("PushPhase")

    /** One reading of the injected clock, in the unit this class stores. */
    private fun now(): Long = clock.now().toEpochMilliseconds()

    /**
     * Pushes all pending patches to the server.
     */
    internal suspend fun push(): PhaseResult<PushSummary> {
        val session = authRepository.currentSession.value
        val signedIn = session is Session.SignedIn
        if (signedIn) phases.setPushStatus(isPushing = true)

        // One exit for "there is nothing to push", whether that is because nobody is
        // signed in or because the queue is empty. They are the same answer — an empty
        // summary over an untouched outbox — and splitting them across two returns cost
        // the budget the discard below needs.
        val plan = if (signedIn) {
            planPush().getOrElse { return PhaseResult.Failed(it.toAppError()) }
        } else {
            null
        }

        if (plan == null) {
            phases.setPushStatus(isPushing = false)
            return PhaseResult.Ok(PushSummary(0, 0, 0))
        }

        // Smart-cast does not work across method boundaries on a sealed type's
        // concrete subclass. Unpack explicitly so the compiler knows the type.
        val ready = when (plan) {
            is PushPlan.Ready -> plan
            PushPlan.Empty -> {
                phases.setPushStatus(isPushing = false)
                return PhaseResult.Ok(PushSummary(0, 0, 0))
            }
        }

        val pending = ready.pending
        // D2 fix: use the scope captured at plan-building time, not a re-read.
        // This scope is the authoritative owner of all patches in this plan.
        val activeScope = ready.scope

        return try {
            // Give the API a mutable reference to pending so a test can intercept
            // and mutate it between plan-building and response-processing (模拟 D1 竞态).
            api.pendingRef = pending
            val response = api.batchPush(ready.request)

            // `patches` is derived from `pending` AFTER batchPush returns so that any
            // mutations made by onPushInFlight (inside batchPush) are reflected here.
            val patches = pending.map { entity -> StableJson.decodeFromString<DeltaPatch>(entity.payload) }

            // REQ-UA-018: is the account that authorised this request still the one
            // signed in? Compared by account, not by session.
            val requestedBy = session.accountIdOrNull
            val nowSignedInAs = authRepository.currentSession.value.accountIdOrNull

            if (requestedBy != nowSignedInAs) {
                log.e {
                    "Push response discarded: requested under [$requestedBy], " +
                        "now [$nowSignedInAs]. ${pending.size} row(s) stay queued."
                }
                val discarded = PushSummary(0, 0, 0, discarded = response.results.size)
                phases.recordPushResult(Result.success(discarded))
                phases.setPushStatus(isPushing = false)
                return PhaseResult.Ok(discarded)
            }

            var succeeded = 0
            var failed = 0
            var lost = 0
            var superseded = 0

            response.results.forEach { result ->
                val patch = patches.firstOrNull { p -> p.patchId == result.patchId }

                // D1 fix — superseded:
                // `patch == null` means the outbox row was removed between plan-time and
                // now (a local edit coalesced it away). The server confirmed `ok: true`
                // but this device no longer has the patch. Count it as superseded, skip
                // the outbox delete (already gone), skip settleShadow (already settled).
                if (patch == null) {
                    superseded++
                    return@forEach
                }

                // `lost` is checked before `ok` because the server reports a lost race
                // as `ok: true`. Branching on `ok` first is the original defect #203.
                if (result.ok && result.lost) {
                    lost++
                    outboxDao.delete(result.patchId)
                    resolveLostRace(patch, scope = activeScope)
                } else if (result.ok) {
                    outboxDao.delete(result.patchId)
                    // The version the server just reported, recorded on the same guarded
                    // statement that promotes the state.
                    settleShadow(patch, applied = true, scope = activeScope, serverVersion = result.newVersion)
                    succeeded++
                } else {
                    failed++
                    if (result.isRetriable) {
                        // D2 fix: pass `activeScope` (the plan's scope), not a re-read scope.
                        deferOrDeadLetter(
                            pending.firstOrNull { row -> row.patchId == result.patchId },
                            result,
                            activeScope,
                        )
                    } else {
                        outboxDao.delete(result.patchId)
                        settleShadow(patch, applied = false, scope = activeScope)
                    }
                }
            }

            val summary = PushSummary(
                processed = response.results.size,
                succeeded = succeeded,
                failed = failed,
                lost = lost,
                superseded = superseded,
            )
            phases.recordPushResult(Result.success(summary))
            phases.setPushStatus(isPushing = false)
            PhaseResult.Ok(summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val error = e.toAppError()
            phases.pushFailed(error, pending.size)
            return PhaseResult.Failed(error)
        }
    }

    /**
     * Assembles what to send, or reports why it could not be assembled.
     */
    private suspend fun planPush(): Result<PushPlan?> {
        val nowMillis = now()

        // Read the scope FIRST — it is captured and used throughout response processing.
        // Re-reading would let a profile switch put this cycle's patches under one
        // profile and their shadow under another.
        val activeScope = phases
            .localStorage("sync.scope.read", "read the active sync scope") {
                scopeProvider.current.first()
            }
            .getOrElse { error ->
                phases.pushFailed(error.toAppError(), 0)
                return Result.failure(error)
            }

        val pending = activeScope
            ?.let { s ->
                phases.localStorage("sync.outbox.read", "read the pending changes") {
                    outboxDao.getPending(nowMillis, s.ownerId)
                }
            }
            ?.getOrElse { error ->
                phases.pushFailed(error.toAppError(), 0)
                return Result.failure(error)
            }
            ?: emptyList()

        if (pending.isEmpty()) return Result.success(null)

        return phases
            .localStorage("sync.outbox.decode", "assemble the queued changes") {
                val patches = pending.map { entity -> StableJson.decodeFromString<DeltaPatch>(entity.payload) }
                PushPlan.Ready(
                    scope = activeScope!!,
                    pending = pending.toMutableList(),
                    patches = patches,
                    request = BatchPushRequest(
                        deviceId = idGenerator.next(),
                        profileId = activeScope.profileId,
                        patches = patches,
                    ),
                )
            }
            .fold(
                onSuccess = { Result.success(it as PushPlan?) },
                onFailure = { error ->
                    phases.pushFailed(error.toAppError(), pending.size)
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
    private suspend fun resolveLostRace(patch: DeltaPatch, scope: SyncScope) {
        val typeKey = patch.entityType.key
        val confirmed = shadowDao
            .get(scope.ownerId, scope.profileId, typeKey, patch.entityId)
            ?.confirmedJson

        val reverted = confirmed?.let { document ->
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
                json = shadowDao.get(scope.ownerId, scope.profileId, typeKey, patch.entityId)
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
        val pending: MutableList<SyncOutboxEntity>,
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
