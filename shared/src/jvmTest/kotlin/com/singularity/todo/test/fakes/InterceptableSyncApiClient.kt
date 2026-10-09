package com.singularity.todo.test.fakes

import com.singularity.todo.core.sync.BatchPushRequest
import com.singularity.todo.core.sync.BatchPushResponse
import com.singularity.todo.core.sync.SyncApiClient
import com.singularity.todo.core.sync.SyncEvent
import com.singularity.todo.core.sync.SyncOutboxEntity
import kotlinx.serialization.json.JsonObject

/**
 * A [SyncApiClient] that intercepts every call on a delegate [SyncApiClient].
 *
 * Use when a test needs a real [SyncApiClient] (talking to a test server, a mock,
 * or another fake) but also needs to inject failures, observe in-flight requests,
 * or mutate state during the request/response cycle.
 *
 * The three hooks cover the full lifecycle of a push:
 * - [onPushInFlight] fires after [delegate.batchPush] is called, while the
 *   request is on the wire — for sign-out, profile-switch, and other mutations
 *   that cannot be staged from outside the call
 * - [onBeforeResponseLoop] fires after [delegate.batchPush] returns, before
 *   the caller processes the response — for D1-race mutations that coalesced
 *   a pending row
 * - [failWith] causes [batchPush] to throw before calling the delegate
 *
 * @param delegate The [SyncApiClient] to which calls are delegated after
 *   [failWith] and [onPushInFlight] are applied.
 * @param authenticatedAs The account this transport is authenticated as.
 */
class InterceptableSyncApiClient(
    private val delegate: SyncApiClient,
    val authenticatedAs: String = "user-1",
) : SyncApiClient {

    /** When set, [batchPush] throws this before calling the delegate. */
    var failWith: Throwable? = null

    /**
     * Fires with the request *in flight*, after [delegate.batchPush] is called
     * but before it returns.
     *
     * The seam for everything that can change while a request is on the wire
     * and must not be applied afterwards: a sign-out, a switch to another
     * account, a profile change.
     *
     * The default is a no-op.
     */
    var onPushInFlight: (suspend (request: BatchPushRequest) -> Unit)? = null

    override var onBeforeResponseLoop: (
        pending: MutableList<SyncOutboxEntity>,
        response: BatchPushResponse,
    ) -> Unit = { _, _ -> }

    /** Internal pending list used when firing [onBeforeResponseLoop]. */
    private val internalPending = mutableListOf<SyncOutboxEntity>()

    override suspend fun batchPush(request: BatchPushRequest): BatchPushResponse {
        failWith?.let { throw it }
        val response = delegate.batchPush(request)
        onPushInFlight?.invoke(request)
        onBeforeResponseLoop(internalPending, response)
        return response
    }

    override suspend fun getEventsSince(sinceLsn: Long, limit: Int): List<SyncEvent> {
        failWith?.let { throw it }
        return delegate.getEventsSince(sinceLsn, limit)
    }

    override suspend fun testConnection(): Result<Unit> {
        failWith?.let { throw it }
        return delegate.testConnection()
    }

    override suspend fun getRemoteConfig(): JsonObject? {
        failWith?.let { throw it }
        return delegate.getRemoteConfig()
    }
}
