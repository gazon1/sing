package com.singularity.todo.core.sync

/**
 * API interface for sync operations.
 */
interface SyncApiClient {
    suspend fun batchPush(request: BatchPushRequest): BatchPushResponse
    suspend fun getEventsSince(userId: String, sinceLsn: Long, limit: Int = 50): List<SyncEvent>
    /** Verifies connectivity to the sync endpoint. Returns success if reachable. */
    suspend fun testConnection(userId: String): Result<Unit>
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
}
