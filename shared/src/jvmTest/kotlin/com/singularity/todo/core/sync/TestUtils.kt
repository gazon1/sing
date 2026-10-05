package com.singularity.todo.core.sync

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import kotlinx.serialization.json.JsonElement
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
    var hlc: Hlc? = null
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
        hlc = hlc,
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

    /** Empty by default: an event with no profile applies to whichever scope pulls it. */
    var profileId = ""

    /**
     * The document the event carries, or null for an event that has none.
     *
     * Added because the builder had no such field, which meant **every** event in the
     * suite was built without a payload — and an event without a payload is exactly
     * the case a handler reports as unusable rather than applied. The suite could not
     * notice, because it never built the other kind.
     */
    var data: JsonElement? = null

    fun build(): SyncEvent = SyncEvent(
        serverLsn = serverLsn,
        entityId = entityId,
        entityType = entityType,
        eventType = eventType,
        data = data,
        createdAt = createdAt,
        profileId = profileId,
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
    /**
     * The account this transport is authenticated as.
     *
     * Fixed at construction rather than passed per call, because that is the whole
     * point of the change to [SyncApiClient]: the identity a request acts on is a
     * property of the transport's session, not something a caller chooses. A fake
     * that accepted it per call would model the parameter this phase removes, and a
     * test using it would keep asserting against a contract that no longer exists.
     */
    val authenticatedAs: String = "user-1",
) : SyncApiClient {
    val pushCalls = mutableListOf<BatchPushRequest>()

    /** One entry per pull: the account the transport acted as, and the position. */
    val pullCalls = mutableListOf<Pair<String, Long>>()

    /** When set, both [batchPush] and [getEventsSince] throw it. */
    var failWith: Throwable? = null

    /**
     * Answers for successive pushes, drained before [pushResponse].
     *
     * The constructor takes one response for every call, which is enough for a test
     * about a single cycle and useless for anything that spans two: "the server
     * reported version 7, so does the next patch carry 7" needs the second push to see
     * what the first one caused. Scripting a sequence per call is the difference
     * between testing a state machine and testing one of its states.
     */
    val pushResponses = ArrayDeque<BatchPushResponse>()

    override suspend fun batchPush(request: BatchPushRequest): BatchPushResponse {
        pushCalls.add(request)
        failWith?.let { throw it }
        return if (pushResponses.isNotEmpty()) pushResponses.removeFirst() else pushResponse
    }

    override suspend fun getEventsSince(sinceLsn: Long, limit: Int): List<SyncEvent> {
        pullCalls.add(authenticatedAs to sinceLsn)
        failWith?.let { throw it }
        return pullEvents
            .filter { it.serverLsn > sinceLsn }
            .sortedBy { it.serverLsn }
            .take(limit)
    }

    override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

    override suspend fun getRemoteConfig(): kotlinx.serialization.json.JsonObject? = null
}
