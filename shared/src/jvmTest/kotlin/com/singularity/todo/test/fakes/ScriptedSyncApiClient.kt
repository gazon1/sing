package com.singularity.todo.test.fakes

import com.singularity.todo.core.sync.BatchPushRequest
import com.singularity.todo.core.sync.BatchPushResponse
import com.singularity.todo.core.sync.PatchResult
import com.singularity.todo.core.sync.SyncApiClient
import com.singularity.todo.core.sync.SyncEvent
import com.singularity.todo.core.sync.SyncOutboxEntity
import kotlinx.serialization.json.JsonObject

/**
 * A [SyncApiClient] that plays back a fixed sequence of scripted responses.
 *
 * Use when a test needs deterministic, repeatable API behaviour — for example,
 * a multi-cycle sync where each cycle must see a different server outcome.
 *
 * Key properties:
 * - [pushResponses] is drained in order; when empty, [defaultPushResponse] is used
 * - [pullEvents] are filtered by `serverLsn > sinceLsn` and sorted before returning
 * - [ignoreSinceLsn] models a misbehaving server that ignores cursors
 *
 * @param defaultPushResponse Returned by [batchPush] when [pushResponses] is empty.
 * @param pullEvents All events this client can ever serve.
 * @param authenticatedAs The account this transport is authenticated as.
 * @param ignoreSinceLsn If true, [getEventsSince] ignores its `sinceLsn` argument.
 */
class ScriptedSyncApiClient(
    private val defaultPushResponse: BatchPushResponse = BatchPushResponse(emptyList()),
    private val pullEvents: List<SyncEvent> = emptyList(),
    val authenticatedAs: String = "user-1",
    ignoreSinceLsn: Boolean = false,
) : SyncApiClient {

    private val _pushResponses = ArrayDeque<BatchPushResponse>()
    val pushResponses: ArrayDeque<BatchPushResponse> get() = _pushResponses

    private var _ignoreSinceLsn = ignoreSinceLsn
    var ignoreSinceLsn: Boolean
        get() = _ignoreSinceLsn
        set(value) { _ignoreSinceLsn = value }

    /**
     * Adds a [BatchPushResponse] to the end of the response queue.
     * Responses are drained in FIFO order by [batchPush].
     */
    fun enqueuePushResponse(response: BatchPushResponse) {
        _pushResponses.addLast(response)
    }

    /**
     * Convenience: enqueues a response constructed from the given [PatchResult] items.
     */
    fun enqueuePushResponse(vararg results: PatchResult) {
        enqueuePushResponse(BatchPushResponse(results.toList()))
    }

    override suspend fun batchPush(request: BatchPushRequest): BatchPushResponse {
        return if (_pushResponses.isNotEmpty()) {
            _pushResponses.removeFirst()
        } else {
            defaultPushResponse
        }
    }

    override suspend fun getEventsSince(sinceLsn: Long, limit: Int): List<SyncEvent> {
        return pullEvents
            .filter { _ignoreSinceLsn || it.serverLsn > sinceLsn }
            .sortedBy { it.serverLsn }
            .take(limit)
    }

    override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

    override suspend fun getRemoteConfig(): JsonObject? = null

    override var onBeforeResponseLoop: (
        pending: MutableList<SyncOutboxEntity>,
        response: BatchPushResponse,
    ) -> Unit = { _, _ -> }
}
