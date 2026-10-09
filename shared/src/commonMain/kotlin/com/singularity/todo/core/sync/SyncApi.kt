package com.singularity.todo.core.sync

import kotlinx.serialization.json.JsonObject

/**
 * API interface for sync operations.
 *
 * ## No method takes an owner id, and that is the security property
 *
 * Every method here used to take a `userId: String` that the caller supplied and
 * the server would have believed. A client that passes another account's id reads
 * that account's events and writes into its data — and the request is perfectly
 * well-formed, so nothing on either side can tell it from a legitimate one.
 *
 * The id the server acts on comes from the session it authenticated, and the only
 * way to keep a client from *asking* for someone else's data is to remove the
 * parameter: an argument the client can set is an argument the client can set
 * wrongly, and a wrong value here is an authorisation bypass rather than a bug.
 *
 * The consequence for tests is that [FakeSyncApiClient] is constructed with the
 * identity it stands in for, rather than being handed one per call — the identity
 * is a property of the transport, and a fake that accepts it per call would model
 * the thing being removed.
 */
interface SyncApiClient {
    /**
     * Called after [batchPush] returns, before [PushPhase.push] processes the response.
     *
     * The seam for mutations that can occur while a request is on the wire: a local
     * edit that coalesces a pending row, a sign-out, a profile switch. The callback
     * receives the pending list (which is a mutable copy; the plan is unaffected) and
     * the server response. Mutations applied to the list here are visible when
     * [PushPhase.push] computes the per-entity patch list for outcome processing.
     *
     * Production clients may use this for logging, metrics, or optimistic UI updates.
     * The default implementation does nothing.
     */
    var onBeforeResponseLoop: (
        pending: MutableList<SyncOutboxEntity>,
        response: BatchPushResponse,
    ) -> Unit

    suspend fun batchPush(request: BatchPushRequest): BatchPushResponse

    /**
     * Events for the authenticated scope, after [sinceLsn].
     *
     * The scope is not an argument: it is whoever the transport is authenticated as.
     */
    suspend fun getEventsSince(sinceLsn: Long, limit: Int = 50): List<SyncEvent>

    /** Verifies connectivity to the sync endpoint. Returns success if reachable. */
    suspend fun testConnection(): Result<Unit>

    /**
     * Fetches the remote runtime configuration as a raw JSON object.
     * Returns null if no remote config is set (server returned empty / 404).
     *
     * The returned [JsonObject] is then validated and deserialized by
     * [RemoteConfigSnapshot.validate]. The sync schema serves no configuration,
     * so the RPC-backed client answers `null` here; see
     * [SupabaseSyncApiClient.getRemoteConfig] for why that is the accurate
     * answer rather than a stub.
     */
    suspend fun getRemoteConfig(): JsonObject?
}
