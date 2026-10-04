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
     * operations had errors, the engine finished its cycle without being coalesced).
     */
    data class Success(val push: Result<PushSummary>, val pull: Result<PullSummary>) : SyncOutcome

    /**
     * The sync was skipped because another sync was already running.
     * The caller may fire a follow-up sync once the running one completes.
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
    private val prefs: SyncPrefs,
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
        val patch = buildPatch(entity)
        val payload = json.encodeToString(patch)

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
        val push = push()
        val pull = pull(sinceLsn = prefs.lastLsn)
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

            response.results.forEach { result ->
                if (result.ok) {
                    outboxDao.delete(result.patchId)
                    succeeded++
                } else {
                    failed++
                    if (result.isRetriable) {
                        deferOrDeadLetter(pending.firstOrNull { it.patchId == result.patchId }, result)
                    } else {
                        // The server will never accept this patch. Keeping it would
                        // block every patch behind it, forever.
                        outboxDao.delete(result.patchId)
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
     * Pulls events from the server since [SyncPrefs.lastLsn].
     */
    private suspend fun pull(sinceLsn: Long): Result<PullSummary> {
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
            // after the loop, so it is the last position that was actually applied.
            prefs.setLastLsn(maxLsn)
            // Stamp lastSuccessfulSyncAt so the UI "Last synced" field stays current.
            prefs.recordSuccessfulSync()

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
     * Builds a DeltaPatch from a SyncableEntity.
     *
     * `isDelete` is deliberately always `false`. Deletions propagate as **state**:
     * repositories re-read the entity after a soft delete and enqueue it, so
     * `archivedAt` / `isDeleted` ride along in the snapshot that `ops = emptyList()` +
     * `shadowChecksum` already carries. That needs no server-side change, and — unlike
     * a tombstone — it also covers `restore` through the same path.
     *
     * `deltaPatchDelete` exists in the protocol and would express a true tombstone,
     * but it has never been exercised against the server; adopting it is a separate
     * protocol decision. See docs/decisions/2026-09-27-write-layer-soundness.md.
     */
    private fun buildPatch(entity: SyncableEntity): DeltaPatch {
        val state = entity.toJson()
        val checksum = ConflictResolver.checksum(state)

        return DeltaPatch(
            patchId = idGenerator.next(),
            entityId = entity.syncId,
            entityType = entity.docType,
            baseVersion = entity.syncServerVersion,
            isDelete = false,
            shadowChecksum = checksum,
            ops = emptyList(),
            timestampMs = System.currentTimeMillis(),
        )
    }
}
