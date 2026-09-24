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
     * TODO: Replace with actual Supabase / edge-function call in sync backend MR.
     */
    suspend fun getRemoteConfig(): JsonObject?
}

/**
 * Stub implementation of SyncApiClient for compilation.
 * TODO: Replace with actual Supabase implementation once SDK is properly integrated.
 */
class SupabaseSyncApiClient : SyncApiClient {
    override suspend fun batchPush(request: BatchPushRequest): BatchPushResponse {
        // TODO: Implement with Supabase Edge Functions
        return BatchPushResponse(emptyList())
    }

    override suspend fun getEventsSince(userId: String, sinceLsn: Long, limit: Int): List<SyncEvent> {
        // TODO: Implement with Supabase Edge Functions
        return emptyList()
    }

    override suspend fun testConnection(userId: String): Result<Unit> {
        // TODO: Implement with Supabase Edge Functions
        return Result.success(Unit)
    }

    override suspend fun getRemoteConfig(): JsonObject? {
        // TODO: Implement with Supabase Edge Function or remote-config endpoint
        return null
    }
}
