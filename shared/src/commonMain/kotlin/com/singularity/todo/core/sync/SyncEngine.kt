package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.work.SyncWorkScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * Summary of a push operation.
 */
data class PushSummary(val processed: Int, val succeeded: Int, val failed: Int)

/**
 * Summary of a pull operation.
 *
 * [dropped] counts events this client could not apply. It is the difference between
 * "the server sent three events and two are stored" and "three events were consumed
 * and one was thrown away" — a distinction the old three-field summary could not
 * express, which is why a permanently unappliable event was invisible.
 */
data class PullSummary(val received: Int, val applied: Int, val conflicts: Int, val dropped: Int = 0)

/**
 * Outcome of a single sync run (push + pull).
 */
sealed interface SyncOutcome {
    /**
     * A sync completed (push and/or pull ran to completion — even if individual
     * operations had errors, the engine finished its cycle).
     */
    data class Success(val push: Result<PushSummary>, val pull: Result<PullSummary>) : SyncOutcome

    /**
     * No cycle ran, and none will: the request could not be handed to the
     * [SyncCoordinator], which happens when the coordinator is closed.
     *
     * It is deliberately **not** "another sync was already running". A request that
     * arrives mid-cycle is absorbed by the conflated channel and answered with the
     * outcome of the cycle that served it — the caller gets its work done, not a
     * rejection it now has to reason about. This case means there is no work left to
     * do, which is a genuinely different thing and needs a different reaction.
     */
    data class Skipped(val reason: String) : SyncOutcome
}

/**
 * Status of the sync engine.
 *
 * [NoConnection] is an operational state — the device is offline, not failed.
 * All other errors are wrapped in [Failure].
 */
sealed interface SyncEngineStatus {
    data object Idle : SyncEngineStatus
    data object Pushing : SyncEngineStatus
    data object Pulling : SyncEngineStatus
    data object NoConnection : SyncEngineStatus
    data class Failure(val error: AppError) : SyncEngineStatus

    fun isRunning(): Boolean = this is Pushing || this is Pulling

    fun isSuccess(): Boolean = this is Idle || this is NoConnection
}

/**
 * Outcome of applying a [SyncEvent] during pull.
 */
sealed interface ApplyOutcome {
    data object Applied : ApplyOutcome
    data class Conflict(val reason: String) : ApplyOutcome
}

/**
 * Fun interface for applying a pull event to a local entity.
 */
fun interface EntityApply {
    suspend fun apply(event: SyncEvent): ApplyOutcome
}

/**
 * Sync engine — orchestrates push and pull operations.
 *
 * Polling is the caller's responsibility (see [SyncRunner]). This class only
 * provides [enqueue], [syncOnce], and [registerHandler].
 */
internal class SyncEngine(
    private val log: Logger,
    private val api: SyncApiClient,
    private val authRepository: AuthRepository,
    private val outboxDao: SyncOutboxDao,
    private val deadLetterDao: SyncDeadLetterDao,
    private val idGenerator: IdGenerator,
    private val stateRepository: SyncStateRepository,
    private val scopeProvider: SyncScopeProvider,
    private val shadowDao: SyncShadowDao,
    private val patchBuilder: SyncPatchBuilder,
    private val scheduler: SyncWorkScheduler,
    private val retryPolicy: PatchRetryPolicy = PatchRetryPolicy(),
    private val scope: AutoCloseableCoroutineScope,
    private val crashReporter: CrashReportingPort,
) : AutoCloseable by scope {
    private val json = StableJson

    private val _status = MutableStateFlow<SyncEngineStatus>(SyncEngineStatus.Idle)
    val status: StateFlow<SyncEngineStatus> = _status.asStateFlow()

    private val _lastPush = MutableStateFlow<Result<PushSummary>?>(null)
    val lastPush: StateFlow<Result<PushSummary>?> = _lastPush.asStateFlow()

    private val _lastPull = MutableStateFlow<Result<PullSummary>?>(null)
    val lastPull: StateFlow<Result<PullSummary>?> = _lastPull.asStateFlow()

    // Per-entity pull handlers (registered by TasksDiModule, NotesDiModule, etc.)
    private val _handlers = MutableStateFlow<Map<DocType, EntityApply>>(emptyMap())
    val handlers: Map<DocType, EntityApply> get() = _handlers.value

    init {
        // React to session changes: enqueue push when signed in, cancel when signed out.
        // WorkManager handles back-off and persistence across process death.
        scope.launch {
            authRepository.currentSession.collect { session ->
                when (session) {
                    is Session.SignedIn -> scheduler.enqueuePush()

                    is Session.Anonymous,
                    is Session.SignedOut,
                    is Session.Loading,
                    -> scheduler.cancelPush()
                }
            }
        }
    }

    /**
     * Registers a handler for pull events of the given [DocType].
     */
    fun registerHandler(docType: DocType, apply: EntityApply) {
        _handlers.value += (docType to apply)
    }

    /**
     * Enqueues an entity change for sync.
     */
    suspend fun enqueue(entity: SyncableEntity): Result<Unit> = runCatchingResult {
        val active = scopeProvider.current.first()
            ?: return@runCatchingResult
        val patch = patchBuilder.build(entity, active, System.currentTimeMillis())
        val payload = json.encodeToString(patch)

        // Coalesce per entity before inserting, so an entity has at most one patch
        // in flight. The new patch is built against the previous in-flight state, so
        // it carries every field the one it replaces carried — replacing loses
        // nothing. Without this the outbox grows without bound under fast editing,
        // and the shadow's "promote only if this patch still owns the marker" guard
        // could never fire.
        outboxDao.deleteByEntity(entity.syncId)
        outboxDao.insert(
            SyncOutboxEntity(
                patchId = patch.patchId,
                entityId = entity.syncId,
                entityType = entity.docType.key,
                payload = payload,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Runs one push + pull cycle.
     */
    internal suspend fun syncOnce(): SyncOutcome {
        val scope = scopeProvider.current.first()
            ?: return SyncOutcome.Skipped("No active sync scope (signed out, or no profile)")
        val push = push()
        val pull = pull(scope, sinceLsn = stateRepository.get(scope).lastLsn)
        return SyncOutcome.Success(push, pull)
    }

    /**
     * Pushes all pending patches to the server.
     */
    internal suspend fun push(): Result<PushSummary> {
        val session = authRepository.currentSession.value
        if (session !is Session.SignedIn) {
            return Result.success(PushSummary(0, 0, 0))
        }

        _status.value = SyncEngineStatus.Pushing
        val now = System.currentTimeMillis()
        val pending = outboxDao.getPending(now)
        if (pending.isEmpty()) {
            _status.value = SyncEngineStatus.Idle
            return Result.success(PushSummary(0, 0, 0))
        }

        val patches = pending.map { entity ->
            json.decodeFromString<DeltaPatch>(entity.payload)
        }

        val request = BatchPushRequest(
            deviceId = idGenerator.next(),
            patches = patches,
        )

        return try {
            val response = api.batchPush(request)
            var succeeded = 0
            var failed = 0

            val active = scopeProvider.current.first()
            response.results.forEach { result ->
                val patch = patches.firstOrNull { it.patchId == result.patchId }
                if (result.ok) {
                    outboxDao.delete(result.patchId)
                    if (active != null && patch != null) settleShadow(patch, applied = true, scope = active)
                    succeeded++
                } else {
                    failed++
                    if (result.isRetriable) {
                        deferOrDeadLetter(pending.firstOrNull { it.patchId == result.patchId }, result)
                    } else {
                        // The server will never accept this patch. Keeping it would
                        // block every patch behind it, forever. Releasing the shadow
                        // marker is what makes the next local edit re-send the
                        // fields this one was carrying, instead of diffing against a
                        // state the server never reached.
                        outboxDao.delete(result.patchId)
                        if (active != null && patch != null) settleShadow(patch, applied = false, scope = active)
                    }
                }
            }

            val summary = PushSummary(response.results.size, succeeded, failed)
            _lastPush.value = Result.success(summary)
            _status.value = SyncEngineStatus.Idle
            Result.success(summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val err: AppError = e as? AppError
                ?: AppError.Unknown(
                    e.message
                        ?: "",
                )
            _lastPush.value = Result.failure(err)
            log.e(e) { "Batch push failed [count=${pending.size}]" }
            crashReporter.report(e, "sync.push_failed")
            _status.value = SyncEngineStatus.Failure(err)
            Result.failure(err)
        }
    }

    /**
     * Backs a rejected patch off, or gives up on it.
     *
     * The count lives in SQL (`attempts = attempts + 1`) and is read back before
     * the decision, so the increment and the decision cannot disagree when two
     * cycles overlap.
     *
     * Giving up means *moving* the patch, never deleting it: a change the user made
     * should not be destroyed because the system could not deliver it.
     */
    private suspend fun deferOrDeadLetter(entity: SyncOutboxEntity?, result: PatchResult) {
        if (entity == null) return

        val attempts = (outboxDao.attemptsOf(entity.patchId) ?: entity.attempts) + 1
        val reason = result.error ?: "Unknown error"

        if (retryPolicy.isExhausted(attempts)) {
            // The patch is set aside, not lost — see SyncDeadLetterEntity. Its in-flight
            // shadow marker is released with it, so the confirmed state stays at what
            // the server really has and the next local edit re-sends these fields.
            scopeProvider.current.first()?.let { active ->
                json.decodeFromString<DeltaPatch>(entity.payload).let { patch ->
                    shadowDao.release(
                        ownerId = active.ownerId,
                        profileId = active.profileId,
                        entityType = patch.entityType.key,
                        entityId = patch.entityId,
                        patchId = patch.patchId,
                    )
                }
            }
            deadLetterDao.insert(
                SyncDeadLetterEntity(
                    patchId = entity.patchId,
                    entityId = entity.entityId,
                    entityType = entity.entityType,
                    payload = entity.payload,
                    createdAt = entity.createdAt,
                    failedAt = System.currentTimeMillis(),
                    attempts = attempts,
                    lastError = reason,
                ),
            )
            outboxDao.delete(entity.patchId)
            log.e {
                "Patch ${entity.patchId} failed $attempts times; moved to the " +
                    "dead letter store [error=$reason]"
            }
            return
        }

        val delay = retryPolicy.delayFor(attempts)
        outboxDao.markFailed(
            id = entity.patchId,
            error = reason,
            nextAttemptAt = System.currentTimeMillis() + delay,
        )
        log.w { "Patch ${entity.patchId} failed (attempt $attempts); retrying in ${delay}ms" }
    }

    /**
     * Pulls events from the server for [scope], starting after the cursor stored for
     * that scope. The caller passes the starting position so the value it applies is
     * the value it read — re-reading the cursor inside would allow a profile switch
     * between the read and the write to store one scope's position under another.
     */
    private suspend fun pull(scope: SyncScope, sinceLsn: Long): Result<PullSummary> {
        val session = authRepository.currentSession.value
        if (session !is Session.SignedIn) {
            return Result.success(PullSummary(0, 0, 0))
        }

        _status.value = SyncEngineStatus.Pulling

        return try {
            val events = api.getEventsSince(session.userId.value, sinceLsn)
            var applied = 0
            var conflicts = 0
            var dropped = 0
            var maxLsn = sinceLsn

            for (event in events) {
                val handler = handlers[event.entityType]
                if (handler == null) {
                    // The cursor does NOT advance past this event, and the pull stops
                    // here. The previous code did `maxLsn = maxOf(maxLsn, lsn)` first
                    // and then `?: return@forEach`, so an event whose type this client
                    // cannot handle advanced the cursor anyway and was never applied
                    // again: the server considered it delivered, the client considered
                    // it done, and the data was gone. The only trace was a pull summary
                    // that said it had received the event and applied nothing.
                    dropped++
                    log.w {
                        "Dropping event at lsn=${event.serverLsn} for unknown type " +
                            "${event.entityType.key}; cursor stays at $maxLsn"
                    }
                    break
                }

                when (handler.apply(event)) {
                    is ApplyOutcome.Applied -> applied++
                    is ApplyOutcome.Conflict -> conflicts++
                }
                maxLsn = maxOf(maxLsn, event.serverLsn)
            }

            // Persist the server LSN so the next pull resumes from this point. Written
            // after the loop, so it is the last position that was actually applied, and
            // against the scope that produced it — a cursor written to whichever scope
            // happens to be current is how two profiles end up sharing a position.
            stateRepository.setLastLsn(scope, maxLsn)
            // Stamp lastSuccessfulSyncAt so the UI "Last synced" field stays current.
            stateRepository.recordSuccessfulSync(scope, System.currentTimeMillis())

            val summary = PullSummary(events.size, applied, conflicts, dropped)
            _lastPull.value = Result.success(summary)
            _status.value = SyncEngineStatus.Idle
            Result.success(summary)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val err: AppError = e as? AppError
                ?: AppError.Unknown(
                    e.message
                        ?: "",
                )
            _lastPull.value = Result.failure(err)
            log.e(e) { "Pull failed [sinceLsn=$sinceLsn]" }
            crashReporter.report(e, "sync.pull_failed")
            _status.value = SyncEngineStatus.Failure(err)
            Result.failure(err)
        }
    }

    /**
     * Moves the in-flight shadow to confirmed, or releases it.
     *
     * Called for every patch the server answers for, and never for one it did not:
     * a patch that is still in the outbox owns its in-flight marker, and a shadow
     * promoted for a patch the server never saw would make the next edit diff
     * against a state the server does not have, which is silent divergence rather
     * than a visible failure.
     */
    private suspend fun settleShadow(patch: DeltaPatch, applied: Boolean, scope: SyncScope) {
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
