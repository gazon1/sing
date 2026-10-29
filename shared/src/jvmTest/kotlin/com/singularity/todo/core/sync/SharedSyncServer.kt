@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * In-memory sync server shared by several [SyncApiClient] instances.
 *
 * Each instance wraps the same [SharedSyncServer] but is authenticated as a
 * different account, so pushes from one become pull events for the others.
 *
 * Limitation (shortcut): this is a dumb event log. It does not merge fields,
 * enforce versions, or carry entity documents in pull events — only the
 * protocol envelope. It is enough to prove a push on A reaches B's pull cursor;
 * it is not a replacement for Supabase. Upgrade when a carrier test needs
 * real cross-device merge.
 */
class SharedSyncServer {
    private val mutex = Mutex()
    private val events = mutableListOf<SyncEvent>()
    private var lsn: Long = 0L

    /** One client transport authenticated as [account]. */
    fun client(account: String): SyncApiClient = SharedSyncClient(this, account)

    internal suspend fun push(
        request: BatchPushRequest,
        @Suppress("UNUSED_PARAMETER") account: String,
    ): BatchPushResponse =
        mutex.withLock {
            val results = request.patches.map { patch ->
                lsn += 1
                val newLsn = lsn
                val data = buildJsonObject {
                    put("entityId", JsonPrimitive(patch.entityId))
                    put("entityType", JsonPrimitive(patch.entityType.name))
                    put("isDelete", JsonPrimitive(patch.isDelete))
                    put("ops", buildJsonArray { patch.ops.forEach { op -> add(JsonPrimitive(op.toString())) } })
                }
                events += SyncEvent(
                    serverLsn = newLsn,
                    entityId = patch.entityId,
                    entityType = patch.entityType,
                    eventType = if (patch.isDelete) SyncEventType.DELETED else SyncEventType.UPDATED,
                    data = data,
                    createdAt = System.currentTimeMillis(),
                    profileId = request.profileId,
                )
                PatchResult(patchId = patch.patchId, ok = true, newVersion = newLsn)
            }
            BatchPushResponse(results)
        }

    internal suspend fun pull(sinceLsn: Long, limit: Int): List<SyncEvent> =
        mutex.withLock {
            events.filter { it.serverLsn > sinceLsn }.sortedBy { it.serverLsn }.take(limit)
        }

    internal suspend fun testConnection(): Result<Unit> = Result.success(Unit)
}

private class SharedSyncClient(private val server: SharedSyncServer, val authenticatedAs: String) : SyncApiClient {
    override var onBeforeResponseLoop: (MutableList<SyncOutboxEntity>, BatchPushResponse) -> Unit = { _, _ -> }

    override suspend fun batchPush(request: BatchPushRequest): BatchPushResponse =
        server.push(request, authenticatedAs)

    override suspend fun getEventsSince(sinceLsn: Long, limit: Int): List<SyncEvent> =
        server.pull(sinceLsn, limit)

    override suspend fun testConnection(): Result<Unit> = server.testConnection()

    override suspend fun getRemoteConfig(): kotlinx.serialization.json.JsonObject? = null
}
