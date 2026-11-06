@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.test.fakes

import com.singularity.todo.core.sync.BatchPushRequest
import com.singularity.todo.core.sync.BatchPushResponse
import com.singularity.todo.core.sync.DeltaPatch
import com.singularity.todo.core.sync.FieldOp
import com.singularity.todo.core.sync.PatchResult
import com.singularity.todo.core.sync.SyncApiClient
import com.singularity.todo.core.sync.SyncEvent
import com.singularity.todo.core.sync.SyncEventType
import com.singularity.todo.core.sync.SyncOutboxEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/**
 * In-memory sync server shared by several [SyncApiClient] instances.
 *
 * Each instance wraps the same [SharedSyncServer] but is authenticated as a
 * different account, so pushes from one become pull events for the others.
 *
 * ## What it does and does not do
 *
 * It keeps the document every entity has, the way the server keeps the row, and
 * every pull event carries that document. The client applies a pull event by
 * decoding its payload as the entity — so an event that carried only the
 * protocol envelope was a pull that could not be applied, and a carrier that
 * needs a real cross-device merge could not exist.
 *
 * What it still does not do: merge fields, enforce versions, or resolve a
 * conflict the way the server's per-field last-writer-wins does. It applies a
 * patch's operations to the document it holds and hands the result on. It is
 * enough to prove that a change made on one device reaches the other one and
 * lands on its row; it is not a replacement for Supabase.
 *
 * ## Why it lives in `commonMain` and not in a test source set
 *
 * A second app instance is a *desktop* scenario: one Koin graph per instance,
 * each with its own database file, talking to one server. The fixture therefore
 * has to be visible to `:desktopApp`'s test source set as well as to `:shared`'s
 * own — and a test source set of one module is invisible to another. The
 * cross-module home for a test double in this repository is
 * `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/`, where the
 * other shared doubles (`FakeAppDatabase`, `FakeTextGen`, the fake
 * repositories) already live — the arrangement recorded in
 * `docs/decisions/2026-09-09-preview-with-koin-helper.md`.
 *
 * It is a double, not production code: nothing in the app resolves it, and the
 * sync port it stands in for ([SyncApiClient]) is bound by the DI module, not by
 * this class.
 */
class SharedSyncServer {
    private val mutex = Mutex()
    private val events = mutableListOf<SyncEvent>()

    /**
     * The document the server holds for each entity.
     *
     * Keyed by doc type and entity id, because two types may carry the same id
     * and a row is identified by both.
     */
    private val documents = mutableMapOf<String, JsonObject>()
    private var lsn: Long = 0L

    /** One client transport authenticated as [account]. */
    fun client(account: String): SyncApiClient = SharedSyncClient(this, account)

    internal suspend fun push(
        request: BatchPushRequest,
        @Suppress("UNUSED_PARAMETER") account: String,
    ): BatchPushResponse =
        mutex.withLock {
            val results =
                request.patches.map { patch ->
                    lsn += 1
                    val newLsn = lsn
                    val key = "${patch.entityType.key}:${patch.entityId}"
                    val document = applyPatch(documents[key] ?: JsonObject(emptyMap()), patch)
                    if (!patch.isDelete) documents[key] = document
                    events +=
                        SyncEvent(
                            serverLsn = newLsn,
                            entityId = patch.entityId,
                            entityType = patch.entityType,
                            eventType = if (patch.isDelete) SyncEventType.DELETED else SyncEventType.UPDATED,
                            data = document,
                            createdAt = Clock.System.now().toEpochMilliseconds(),
                            profileId = request.profileId,
                        )
                    PatchResult(patchId = patch.patchId, ok = true, newVersion = newLsn)
                }
            BatchPushResponse(results)
        }

    /**
     * The document after the patch's operations have been applied to it.
     *
     * The patch is a difference, not a snapshot: it names the fields that
     * changed and the value each one took. So the document the server holds is
     * what the last patch was applied to, and the result is the document the
     * next device reads.
     */
    private fun applyPatch(
        base: JsonObject,
        patch: DeltaPatch,
    ): JsonObject {
        if (patch.isDelete) return base
        val fields = base.toMutableMap()
        for (change in patch.ops) {
            when (change.op) {
                FieldOp.SET -> {
                    val value = change.value
                    if (value == null) fields.remove(change.field) else fields[change.field] = value
                }

                FieldOp.UNSET -> {
                    fields.remove(change.field)
                }

                FieldOp.APPEND -> {
                    val current = fields[change.field] as? JsonArray
                    val added = change.value
                    if (current != null && added != null) fields[change.field] = JsonArray(current + added)
                }

                FieldOp.REMOVE -> {
                    val current = fields[change.field] as? JsonArray
                    val removed = change.value
                    if (current != null && removed != null) {
                        fields[change.field] = JsonArray(current.filterNot { it == removed })
                    }
                }
            }
        }
        return JsonObject(fields)
    }

    internal suspend fun pull(
        sinceLsn: Long,
        limit: Int,
    ): List<SyncEvent> =
        mutex.withLock {
            events.filter { it.serverLsn > sinceLsn }.sortedBy { it.serverLsn }.take(limit)
        }

    internal suspend fun testConnection(): Result<Unit> = Result.success(Unit)
}

private class SharedSyncClient(private val server: SharedSyncServer, val authenticatedAs: String) : SyncApiClient {
    override var onBeforeResponseLoop: (MutableList<SyncOutboxEntity>, BatchPushResponse) -> Unit = { _, _ -> }

    override suspend fun batchPush(request: BatchPushRequest): BatchPushResponse = server.push(request, authenticatedAs)

    override suspend fun getEventsSince(
        sinceLsn: Long,
        limit: Int,
    ): List<SyncEvent> = server.pull(sinceLsn, limit)

    override suspend fun testConnection(): Result<Unit> = server.testConnection()

    override suspend fun getRemoteConfig(): JsonObject? = null
}
