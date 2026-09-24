package com.singularity.todo.core.sync

import kotlinx.serialization.json.JsonObject

/**
 * API interface for sync operations.
 */
interface SyncApiClient {
    suspend fun batchPush(request: BatchPushRequest): BatchPushResponse
    suspend fun getEventsSince(userId: String, sinceLsn: Long, limit: Int = 50): List<SyncEvent>

    /** Verifies connectivity to the sync endpoint. Returns success if reachable. */
    suspend fun testConnection(userId: String): Result<Unit>

    /**
     * Fetches the remote runtime configuration as a raw JSON object.
     * Returns null if no remote config is set (server returned empty / 404).
     *
     * The returned [JsonObject] is then validated and deserialized by
     * [RemoteConfigSnapshot.validate].
     *
     * TODO(#sync): Replace with actual Supabase / edge-function call.
     */
    suspend fun getRemoteConfig(): JsonObject?
}

/**
 * Supabase REST API-backed implementation of [SyncApiClient].
 * Uses Supabase PostgREST for push/pull, edge functions for remote config.
 */
class SupabaseSyncApiClient : SyncApiClient {
    override suspend fun batchPush(request: BatchPushRequest): BatchPushResponse {
        // TODO(#sync): Replace with Supabase Edge Function call
        return BatchPushResponse(emptyList())
    }

    override suspend fun getEventsSince(userId: String, sinceLsn: Long, limit: Int): List<SyncEvent> {
        // TODO(#sync): Replace with Supabase PostgREST query
        return emptyList()
    }

    override suspend fun testConnection(userId: String): Result<Unit> {
        // TODO(#sync): Replace with Supabase health check
        return Result.success(Unit)
    }

    override suspend fun getRemoteConfig(): JsonObject? {
        // TODO(#sync): Replace with Supabase remote config endpoint
        return null
    }
}
