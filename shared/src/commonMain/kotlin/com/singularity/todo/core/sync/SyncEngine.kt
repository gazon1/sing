package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.serialization.StableJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.milliseconds

/**
 * Summary of a push operation.
 */
data class PushSummary(val processed: Int, val succeeded: Int, val failed: Int)

/**
 * Summary of a pull operation.
 */
data class PullSummary(val received: Int, val applied: Int, val conflicts: Int)

/**
 * Outcome of a single sync run (push + pull).
 */
data class SyncOutcome(
    val push: Result<PushSummary>,
    val pull: Result<PullSummary>,
)

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
    private val hlcFactory: HlcFactory,
    private val idGenerator: IdGenerator,
    private val prefs: SyncPrefs,
    private val scope: AutoCloseableCoroutineScope,
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

    /**
     * Registers a handler for pull events of the given [DocType].
     */
    fun registerHandler(docType: DocType, apply: EntityApply) {
        _handlers.value = _handlers.value + (docType to apply)
    }

    /**
     * Enqueues an entity change for sync.
     */
    suspend fun enqueue(entity: SyncableEntity): Result<Unit> = runCatchingResult {
        val hlc = hlcFactory.tick()
        val patch = buildPatch(entity, hlc)
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
        return SyncOutcome(push, pull)
    }

    /**
     * Pushes all pending patches to the server.
     */
    private suspend fun push(): Result<PushSummary> {
        val session = authRepository.currentSession.value
        if (session !is Session.SignedIn) {
            return Result.success(PushSummary(0, 0, 0))
        }

        _status.value = SyncEngineStatus.Pushing
        val pending = outboxDao.getPending()
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
                    if (result.isRetriable) {
                        outboxDao.markFailed(result.patchId, result.error ?: "Unknown error")
                    } else {
                        outboxDao.delete(result.patchId)
                    }
                    failed++
                }
            }

            val summary = PushSummary(response.results.size, succeeded, failed)
            _lastPush.value = Result.success(summary)
            _status.value = SyncEngineStatus.Idle
            Result.success(summary)
        } catch (e: Throwable) {
            val err: AppError = if (e is AppError) e else AppError.Unknown(e)
            _lastPush.value = Result.failure(err)
            log.e(e) { "Batch push failed [count=${pending.size}]" }
            _status.value = SyncEngineStatus.Failure(err)
            Result.failure(err)
        }
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
            var maxLsn = sinceLsn

            events.forEach { event ->
                maxLsn = maxOf(maxLsn, event.serverLsn)
                val handler = handlers[event.entityType] ?: return@forEach
                when (handler.apply(event)) {
                    is ApplyOutcome.Applied -> applied++
                    is ApplyOutcome.Conflict -> conflicts++
                }
            }

            // Persist the server LSN so the next pull resumes from this point.
            prefs.setLastLsn(maxLsn)
            // Stamp lastSuccessfulSyncAt so the UI "Last synced" field stays current.
            prefs.recordSuccessfulSync()

            val summary = PullSummary(events.size, applied, conflicts)
            _lastPull.value = Result.success(summary)
            _status.value = SyncEngineStatus.Idle
            Result.success(summary)
        } catch (e: Throwable) {
            val err: AppError = if (e is AppError) e else AppError.Unknown(e)
            _lastPull.value = Result.failure(err)
            log.e(e) { "Pull failed [sinceLsn=$sinceLsn]" }
            _status.value = SyncEngineStatus.Failure(err)
            Result.failure(err)
        }
    }

    /**
     * Builds a DeltaPatch from a SyncableEntity.
     */
    private fun buildPatch(entity: SyncableEntity, hlc: Hlc): DeltaPatch {
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
