package com.singularity.todo.test.fakes

import com.singularity.todo.core.sync.BatchPushRequest
import com.singularity.todo.core.sync.BatchPushResponse
import com.singularity.todo.core.sync.PatchResult
import com.singularity.todo.core.sync.SyncApiClient
import com.singularity.todo.core.sync.SyncEvent
import com.singularity.todo.core.sync.SyncOutboxEntity
import kotlinx.serialization.json.JsonObject

/**
 * A [SyncApiClient] that records all calls and returns recorded data on demand.
 *
 * Use when a test needs to assert on what calls were made — for example,
 * "push was called exactly once with this payload" — without scripting responses.
 *
 * This client never modifies behaviour between calls; it is purely observational.
 * The recorded calls are available in [pushCalls] and [pullCalls].
 *
 * @param pushResponse The [BatchPushResponse] returned by [batchPush] on every call.
 * @param pullEvents The events returned by [getEventsSince] on every call.
 * @param authenticatedAs The account this transport is authenticated as.
 */
class RecordingSyncApiClient(
    private val pushResponse: BatchPushResponse = BatchPushResponse(emptyList()),
    private val pullEvents: List<SyncEvent> = emptyList(),
    val authenticatedAs: String = "user-1",
) : SyncApiClient {

    /** All [BatchPushRequest] arguments passed to [batchPush], in call order. */
    val pushCalls = mutableListOf<BatchPushRequest>()

    /** All (account, sinceLsn) pairs passed to [getEventsSince], in call order. */
    val pullCalls = mutableListOf<Pair<String, Long>>()

    /** Clears [pushCalls] and [pullCalls]. Call between tests to reset state. */
    fun reset() {
        pushCalls.clear()
        pullCalls.clear()
    }

    override suspend fun batchPush(request: BatchPushRequest): BatchPushResponse {
        pushCalls.add(request)
        return pushResponse
    }

    override suspend fun getEventsSince(sinceLsn: Long, limit: Int): List<SyncEvent> {
        pullCalls.add(authenticatedAs to sinceLsn)
        return pullEvents
            .filter { it.serverLsn > sinceLsn }
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
