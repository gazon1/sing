package com.singularity.todo.core.sync

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

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
 */
class SyncEngine(
    private val api: SyncApiClient,
    private val authRepository: AuthRepository,
    private val outboxDao: SyncOutboxDao,
    private val hlcFactory: HlcFactory,
    syncCoroutineScope: CoroutineScope
) {
    private val scope = syncCoroutineScope
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _status = MutableStateFlow<SyncEngineStatus>(SyncEngineStatus.Idle)
    val status: StateFlow<SyncEngineStatus> = _status.asStateFlow()

    private val _lastPushResult = MutableStateFlow<PushResult?>(null)
    val lastPushResult: StateFlow<PushResult?> = _lastPushResult.asStateFlow()

    private val _lastPullResult = MutableStateFlow<PullResult?>(null)
    val lastPullResult: StateFlow<PullResult?> = _lastPullResult.asStateFlow()

    init {
        scope.launch {
            while (true) {
                val session = authRepository.session.value
                if (session is com.singularity.todo.core.auth.Session.SignedIn) {
                    push()
                    delay(30_000)
                } else {
                    delay(10_000)
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
        if (session !is com.singularity.todo.core.auth.Session.SignedIn) {
            return PushResult(0, 0)
        }

        _status.value = SyncEngineStatus.Pushing
        val pending = outboxDao.getPending()
        if (pending.isEmpty()) {
            _status.value = SyncEngineStatus.Idle
            return PushResult(0, 0)
        }

        val patches = pending.mapNotNull { entity ->
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
            _status.value = SyncEngineStatus.Error(e.message ?: "Push failed")
            result
        }
    }

    /**
     * Pulls events from the server since the given LSN.
     */
    suspend fun pull(sinceLsn: Long = 0): PullResult {
        val session = authRepository.session.value
        if (session !is com.singularity.todo.core.auth.Session.SignedIn) {
            return PullResult(0)
        }

        _status.value = SyncEngineStatus.Pulling

        return try {
            val events = api.getEventsSince(session.userId.value, sinceLsn)
            var received = 0

            events.forEach { event ->
                received++
            }

            val pullResult = PullResult(received)
            _lastPullResult.value = pullResult
            _status.value = SyncEngineStatus.Idle
            pullResult
        } catch (e: Exception) {
            val result = PullResult(0, listOf(e.message ?: "Pull failed"))
            _lastPullResult.value = result
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
