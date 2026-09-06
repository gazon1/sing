package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Result of a push operation.
 */
data class PushResult(
    val pushed: Int,
    val failed: Int,
    val errors: List<String> = emptyList()
)

/**
 * Result of a pull operation.
 */
data class PullResult(
    val received: Int,
    val errors: List<String> = emptyList()
)

/**
 * Sync engine — orchestrates push and pull operations.
 *
 * When the user is signed in, it polls push every 30 seconds.
 * The polling job is canceled automatically when session becomes SignedOut,
 * and a new job is started when session becomes SignedIn again.
 */
class SyncEngine(
    private val log: Logger,
    private val api: SyncApiClient,
    private val authRepository: AuthRepository,
    private val outboxDao: SyncOutboxDao,
    private val hlcFactory: HlcFactory,
    syncCoroutineScope: CoroutineScope
) {
    private val scope = syncCoroutineScope
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _status = MutableStateFlow<SyncEngineStatus>(SyncEngineStatus.Idle)

    private val _lastPushResult = MutableStateFlow<PushResult?>(null)

    private val _lastPullResult = MutableStateFlow<PullResult?>(null)

    // Tracks the current push-loop job — canceled on SignedOut, restarted on SignedIn
    private var pushJob: Job? = null

    init {
        // React to session changes: start/stop the push loop
        scope.launch {
            authRepository.session.collect { session ->
                when (session) {
                    is Session.SignedIn -> {
                        if (pushJob?.isActive != true) {
                            pushJob = scope.launch {
                                while (true) {
                                    try {
                                        push()
                                    } catch (e: Exception) {
                                        log.e(e) { "Push loop failed" }
                                        _status.value = SyncEngineStatus.Error(e.message ?: "Push failed")
                                    }
                                    delay(30_000.milliseconds)
                                }
                            }
                        }
                    }
                    is Session.Anonymous,
                    is Session.SignedOut,
                    is Session.Loading -> {
                        pushJob?.cancel()
                        pushJob = null
                    }
                }
            }
        }
    }

    /**
     * Enqueues an entity change for sync.
     */
    suspend fun enqueue(entity: SyncableEntity) {
        val hlc = hlcFactory.tick()
        val patch = buildPatch(entity, hlc)
        val payload = json.encodeToString(patch)

        outboxDao.insert(
            SyncOutboxEntity(
                patchId = patch.patchId,
                entityId = entity.id,
                entityType = entity.docType.key,
                payload = payload,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Pushes all pending patches to the server.
     */
    suspend fun push(): PushResult {
        val session = authRepository.session.value
        if (session !is Session.SignedIn) {
            return PushResult(0, 0)
        }

        _status.value = SyncEngineStatus.Pushing
        val pending = outboxDao.getPending()
        if (pending.isEmpty()) {
            _status.value = SyncEngineStatus.Idle
            return PushResult(0, 0)
        }

        val patches = pending.map { entity ->
            json.decodeFromString<DeltaPatch>(entity.payload)
        }

        val request = BatchPushRequest(
            deviceId = UUID.randomUUID().toString(),
            patches = patches
        )

        return try {
            val response = api.batchPush(request)
            var pushed = 0
            var failed = 0
            val errors = mutableListOf<String>()

            response.results.forEach { result ->
                if (result.ok) {
                    outboxDao.delete(result.patchId)
                    pushed++
                } else {
                    if (result.isRetriable) {
                        outboxDao.markFailed(result.patchId, result.error ?: "Unknown error")
                    } else {
                        outboxDao.delete(result.patchId)
                    }
                    failed++
                    result.error?.let { errors.add(it) }
                }
            }

            val pushResult = PushResult(pushed, failed, errors)
            _lastPushResult.value = pushResult
            _status.value = SyncEngineStatus.Idle
            pushResult
        } catch (e: Exception) {
            val result = PushResult(0, pending.size, listOf(e.message ?: "Push failed"))
            _lastPushResult.value = result
            log.e(e) { "Batch push failed [count=${pending.size}]" }
            _status.value = SyncEngineStatus.Error(e.message ?: "Push failed")
            result
        }
    }

    /**
     * Pulls events from the server since the given LSN.
     */
    suspend fun pull(sinceLsn: Long = 0): PullResult {
        val session = authRepository.session.value
        if (session !is Session.SignedIn) {
            return PullResult(0)
        }

        _status.value = SyncEngineStatus.Pulling

        return try {
            val events = api.getEventsSince(session.userId.value, sinceLsn)
            var received = 0

            events.forEach { _ ->
                received++
            }

            val pullResult = PullResult(received)
            _lastPullResult.value = pullResult
            _status.value = SyncEngineStatus.Idle
            pullResult
        } catch (e: Exception) {
            val result = PullResult(0, listOf(e.message ?: "Pull failed"))
            _lastPullResult.value = result
            log.e(e) { "Pull failed [sinceLsn=$sinceLsn]" }
            _status.value = SyncEngineStatus.Error(e.message ?: "Pull failed")
            result
        }
    }

    /**
     * Builds a DeltaPatch from a SyncableEntity.
     */
    internal fun buildPatch(entity: SyncableEntity, hlc: Hlc): DeltaPatch {
        val state = entity.toJson()
        val checksum = ConflictResolver.checksum(state)

        return DeltaPatch(
            patchId = UUID.randomUUID().toString(),
            entityId = entity.id,
            entityType = entity.docType,
            baseVersion = entity.serverVersion,
            isDelete = false,
            shadowChecksum = checksum,
            ops = emptyList(),
            timestampMs = System.currentTimeMillis()
        )
    }
}

/**
 * Status of the sync engine.
 */
sealed interface SyncEngineStatus {
    data object Idle : SyncEngineStatus
    data object Pushing : SyncEngineStatus
    data object Pulling : SyncEngineStatus
    data class Error(val message: String) : SyncEngineStatus
}
