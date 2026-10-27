package com.singularity.todo.test.fakes

import com.singularity.todo.core.sync.BatchPushRequest
import com.singularity.todo.core.sync.BatchPushResponse
import com.singularity.todo.core.sync.SyncApiClient
import com.singularity.todo.core.sync.SyncEvent
import com.singularity.todo.core.sync.SyncOutboxEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * A [SyncApiClient] whose remote-config fetch can be told to block.
 *
 * Only [getRemoteConfig] is a real seam here; the sync methods answer emptily, because
 * the remote config is the only thing this fake stands in for. The interesting behaviour
 * is [FetchBehaviour.Blocks], which models a fetch that blocks and cannot be cancelled —
 * the shape a deadline in the *caller's* coroutine cannot end.
 *
 * @param payload What the fetch answers when it answers at all.
 * @param fetchBehaviour How the fetch behaves.
 * @param blockMs How long a blocking fetch ignores cancellation before it answers. It has to
 *   outlast every deadline it is used against, or the fetch answers before the deadline and
 *   the bound is never exercised.
 */
class FakeRemoteConfigApiClient(
    private val payload: JsonObject? = null,
    private val fetchBehaviour: FetchBehaviour = FetchBehaviour.Answer,
    private val blockMs: Long = 60_000L,
) : SyncApiClient {

    /** What the remote-config fetch does. */
    enum class FetchBehaviour {
        /** Answers [payload]. */
        Answer,

        /** Ignores cancellation for [blockMs] and then answers [payload]. */
        Blocks,

        /** Fails. */
        Throws,
    }

    /** How many times the remote config was fetched. */
    var fetchCount: Int = 0
        private set

    override var onBeforeResponseLoop: (
        pending: MutableList<SyncOutboxEntity>,
        response: BatchPushResponse,
    ) -> Unit = { _, _ -> }

    override suspend fun batchPush(request: BatchPushRequest): BatchPushResponse =
        BatchPushResponse(emptyList())

    override suspend fun getEventsSince(sinceLsn: Long, limit: Int, profileId: String): List<SyncEvent> = emptyList()

    override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

    override suspend fun getRemoteConfig(): JsonObject? {
        fetchCount++
        return when (fetchBehaviour) {
            FetchBehaviour.Answer -> payload

            FetchBehaviour.Blocks -> withContext(NonCancellable) {
                withTimeoutOrNull(blockMs) { awaitCancellation() }
                payload
            }

            FetchBehaviour.Throws -> throw IllegalStateException("config server down")
        }
    }
}
