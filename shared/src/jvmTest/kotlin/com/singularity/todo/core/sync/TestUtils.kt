package com.singularity.todo.core.sync

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * DSL builder for Session in tests.
 */
fun session(block: SessionBuilder.() -> Unit = {}): Session = SessionBuilder().apply(block).build()

class SessionBuilder {
    var signedIn = false
    var email = "test@x.com"
    var userId: UserId = UserId.generate()

    fun build(): Session = if (signedIn) {
        Session.SignedIn(userId, email, "access_tok", "refresh_tok")
    } else {
        Session.SignedOut
    }
}

/**
 * DSL builder for DeltaPatch in tests.
 */
fun deltaPatch(block: DeltaPatchBuilder.() -> Unit): DeltaPatch = DeltaPatchBuilder().apply(block).build()

class DeltaPatchBuilder {
    var patchId = "p1"
    var entityId = "e1"
    var entityType = DocType.Task
    var baseVersion = 0L
    var isDelete = false
    var shadowChecksum: String? = null
    val ops = mutableListOf<FieldChange>()

    fun set(field: String, value: String) {
        ops.add(FieldChange(field, FieldOp.SET, JsonPrimitive(value)))
    }

    fun unset(field: String) {
        ops.add(FieldChange(field, FieldOp.UNSET, null))
    }

    fun build(): DeltaPatch = DeltaPatch(
        patchId = patchId,
        entityId = entityId,
        entityType = entityType,
        baseVersion = baseVersion,
        isDelete = isDelete,
        shadowChecksum = shadowChecksum,
        ops = ops,
    )
}

/**
 * DSL builder for SyncEvent in tests.
 */
fun syncEvent(block: SyncEventBuilder.() -> Unit): SyncEvent = SyncEventBuilder().apply(block).build()

class SyncEventBuilder {
    var serverLsn = 1L
    var entityId = "e1"
    var entityType = DocType.Task
    var eventType = SyncEventType.CREATED
    var createdAt = System.currentTimeMillis()

    fun build(): SyncEvent = SyncEvent(
        serverLsn = serverLsn,
        entityId = entityId,
        entityType = entityType,
        eventType = eventType,
        createdAt = createdAt,
    )
}

/**
 * Creates a JsonObject for testing.
 */
fun jsonObject(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
}

/**
 * Fake SyncApiClient for tests — no network needed.
 *
 * [getEventsSince] honours [SyncApiClient.getEventsSince]'s `sinceLsn` and returns
 * events in sequence order. The first version returned its whole scripted list on
 * every call, so any test asserting cursor behaviour passed against a fake that had
 * no cursor to begin with — the assertion was about the fake, not the engine.
 */
class FakeSyncApiClient(
    private val pushResponse: BatchPushResponse = BatchPushResponse(emptyList()),
    private val pullEvents: List<SyncEvent> = emptyList(),
) : SyncApiClient {
    val pushCalls = mutableListOf<BatchPushRequest>()

    /** One entry per pull: the user asked as, and the position they asked from. */
    val pullCalls = mutableListOf<Pair<String, Long>>()

    /** When set, both [batchPush] and [getEventsSince] throw it. */
    var failWith: Throwable? = null

    override suspend fun batchPush(request: BatchPushRequest): BatchPushResponse {
        pushCalls.add(request)
        failWith?.let { throw it }
        return pushResponse
    }

    override suspend fun getEventsSince(userId: String, sinceLsn: Long, limit: Int): List<SyncEvent> {
        pullCalls.add(userId to sinceLsn)
        failWith?.let { throw it }
        return pullEvents
            .filter { it.serverLsn > sinceLsn }
            .sortedBy { it.serverLsn }
            .take(limit)
    }

    override suspend fun testConnection(userId: String): Result<Unit> = Result.success(Unit)

    override suspend fun getRemoteConfig(): kotlinx.serialization.json.JsonObject? = null
}
