@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.work.FakeSyncWorkScheduler
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.TestUsers
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A per-field race this device lost, resolved to the server's state (REQ-OS-026, #203).
 *
 * ## The defect
 *
 * The server reports a lost race as `ok: true, lost: true` — nothing failed, the request
 * was understood, and its fields all lost to values the server already held under a
 * later clock. `PatchResult` had no `lost`, and `StableJson` sets
 * `ignoreUnknownKeys = true`, so the answer was dropped at the parse boundary.
 *
 * `SyncEngine.push()` then branched on `ok` alone and took the success path: the outbox
 * row was deleted and the shadow settled as confirmed, promoting a state the server
 * never took. The next diff was computed against that fiction, so the edit was never
 * re-sent. The user's change existed nowhere, and the device reported it as synced.
 *
 * Verified against the live project before the fix: two writes to one field, the second
 * clock behind, answer `ok: true, lost: true` with `applied: 0, created: 0`.
 *
 * ## The resolution, and the order it has to happen in
 *
 * The row returns to the state the server holds — read from the shadow's own
 * `confirmed_json`, because the server never sends a document with the response
 * (`PatchResult.serverState` exists and nothing populates it). The in-flight marker is
 * released **after** the revert succeeds, so the next diff compares equal states and is
 * empty. Reversing those two steps is a silent infinite loop: the marker drops, the row
 * still holds the losing edit, the diff regenerates it, and it loses again forever.
 */
@Tag("fast")
class SyncEngineLostRaceTest {

    private val clock = MutableClock()
    private val log = Logger.withTag("SyncEngineLostRaceTest")

    private fun engine(
        api: FakeSyncApiClient,
        scope: TestScope,
        tasks: TaskRepository,
        outbox: FakeSyncOutboxDao = FakeSyncOutboxDao(),
        shadow: FakeSyncShadowDao = FakeSyncShadowDao(),
    ): SyncEngine = SyncEngine(
        log = log,
        api = api,
        authRepository = FakeSyncAuthRepository(
            Session.SignedIn(UserId.generate(), "t@x.com", "access", "refresh"),
        ),
        outboxDao = outbox,
        deadLetterDao = FakeSyncDeadLetterDao(),
        idGenerator = SequentialIdGenerator(),
        stateRepository = FakeSyncStateRepository(),
        scopeProvider = FakeSyncScopeProvider(SCOPE),
        shadowDao = shadow,
        patchBuilder = fakeSyncPatchBuilder(shadow),
        writerProvider = { fakeSyncDocumentWriter(tasks = tasks) },
        scheduler = FakeSyncWorkScheduler(),
        retryPolicy = PatchRetryPolicy(),
        clock = clock,
        scope = testScope(scope.backgroundScope),
        crashReporter = NoOpCrashReportingPort(),
    )

    // ── fixtures ───────────────────────────────────────────────────────────────────

    /** The state the server holds: the last state it confirmed for this row. */
    private fun serverTask(title: String): String = StableJson.encodeToString(
        Task(
            id = ENTITY_ID,
            title = title,
            userId = TestUsers.DEFAULT,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        ),
    )

    /** The state the losing patch would have brought it to. */
    private fun localTask(title: String): Task = Task(
        id = ENTITY_ID,
        title = title,
        userId = TestUsers.DEFAULT,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun storedTitle(tasks: FakeTaskRepository): String? = tasks.tasks.value[ENTITY_ID.value]?.title

    /**
     * A row mid-race: the server confirmed one title, a queued patch carries another,
     * and the repository holds the queued one — which is what the device looks like
     * while the patch is in flight.
     */
    private suspend fun raceInProgress(
        tasks: FakeTaskRepository,
        shadow: FakeSyncShadowDao,
        outbox: FakeSyncOutboxDao,
        patchId: String = "p1",
    ) {
        tasks.seed(localTask(LOSING_TITLE))
        shadow.upsert(
            SyncShadowEntity(
                ownerId = SCOPE.ownerId,
                profileId = SCOPE.profileId,
                entityType = DocType.Task.key,
                entityId = ENTITY_ID.value,
                confirmedJson = serverTask(SERVER_TITLE),
                inFlightJson = StableJson.encodeToString(localTask(LOSING_TITLE)),
                inFlightPatchId = patchId,
            ),
        )
        outbox.insert(
            SyncOutboxEntity(
                patchId = patchId,
                ownerId = SCOPE.ownerId,
                entityId = ENTITY_ID.value,
                entityType = DocType.Task.key,
                payload = StableJson.encodeToString(
                    DeltaPatch(
                        patchId = patchId,
                        entityId = ENTITY_ID.value,
                        entityType = DocType.Task,
                        baseVersion = 1,
                        ops = listOf(FieldChange("title", FieldOp.SET, JsonPrimitive(LOSING_TITLE))),
                    ),
                ),
                createdAt = 1L,
            ),
        )
    }

    private fun losing(patchId: String = "p1") = PatchResult(
        patchId = patchId,
        ok = true,
        lost = true,
    )

    private fun winning(patchId: String = "p1") = PatchResult(
        patchId = patchId,
        ok = true,
        newVersion = 4,
    )

    // ── the requirement ────────────────────────────────────────────────────────────

    @Test
    fun `a change that loses the race is not recorded as delivered`() = runTest {
        val tasks = FakeTaskRepository()
        val shadow = FakeSyncShadowDao()
        val outbox = FakeSyncOutboxDao()
        raceInProgress(tasks, shadow, outbox)
        val api = FakeSyncApiClient(pushResponse = BatchPushResponse(listOf(losing())))
        val engine = engine(api, this, tasks, outbox, shadow)

        val summary = engine.push().getOrThrow()

        assertEquals(1, summary.processed)
        assertEquals(0, summary.succeeded, "a lost race is not a delivery")
        assertEquals(1, summary.lost)
        assertTrue(outbox.rows.isEmpty(), "the queued change must not stay as though still owed")
    }

    @Test
    fun `the outcome is counted as neither delivered nor refused`() = runTest {
        // The two readings a three-field summary would have forced: `ok: true` says the
        // user has their edit somewhere the server agrees with, `ok: false` sends an
        // operator hunting a rejection the server never made. Neither is true.
        val tasks = FakeTaskRepository()
        val shadow = FakeSyncShadowDao()
        val outbox = FakeSyncOutboxDao()
        raceInProgress(tasks, shadow, outbox)
        val api = FakeSyncApiClient(pushResponse = BatchPushResponse(listOf(losing())))
        val engine = engine(api, this, tasks, outbox, shadow)

        val summary = engine.push().getOrThrow()

        assertEquals(0, summary.failed, "the server did not refuse this patch")
        assertEquals(1, summary.lost)
        assertEquals(summary.processed, summary.succeeded + summary.failed + summary.lost)
    }

    @Test
    fun `the row returns to what the server holds`() = runTest {
        val tasks = FakeTaskRepository()
        val shadow = FakeSyncShadowDao()
        val outbox = FakeSyncOutboxDao()
        raceInProgress(tasks, shadow, outbox)
        val api = FakeSyncApiClient(pushResponse = BatchPushResponse(listOf(losing())))
        val engine = engine(api, this, tasks, outbox, shadow)

        engine.push()

        assertEquals(
            SERVER_TITLE,
            storedTitle(tasks),
            "the row kept the state of an edit the server refused",
        )
        assertTrue(
            !shadow.state(SCOPE.ownerId, SCOPE.profileId, DocType.Task.key, ENTITY_ID.value)!!
                .confirmedJson.contains(LOSING_TITLE),
            "the confirmed state must not be promoted to the losing edit",
        )
    }

    @Test
    fun `the next change to that row is built on the server's state`() = runTest {
        // This is what makes the resolution safe rather than a loop. The marker is
        // released *after* the revert, so the diff that follows compares the row against
        // confirmed state and finds nothing — the losing edit cannot regenerate itself.
        val tasks = FakeTaskRepository()
        val shadow = FakeSyncShadowDao()
        val outbox = FakeSyncOutboxDao()
        raceInProgress(tasks, shadow, outbox)
        val api = FakeSyncApiClient(pushResponse = BatchPushResponse(listOf(losing())))
        val engine = engine(api, this, tasks, outbox, shadow)
        engine.push()

        val settled = shadow.state(SCOPE.ownerId, SCOPE.profileId, DocType.Task.key, ENTITY_ID.value)!!
        assertNull(settled.inFlightPatchId, "the in-flight marker outlived the lost patch")

        assertTrue(engine.enqueue(SyncableTask(localTask(SERVER_TITLE))).isSuccess)
        val next = StableJson.decodeFromString<DeltaPatch>(outbox.rows.single().payload)
        assertTrue(next.ops.isEmpty(), "the losing edit regenerated itself: ${next.ops}")
    }

    @Test
    fun `a change that wins is unaffected`() = runTest {
        val tasks = FakeTaskRepository()
        val shadow = FakeSyncShadowDao()
        val outbox = FakeSyncOutboxDao()
        raceInProgress(tasks, shadow, outbox)
        val api = FakeSyncApiClient(pushResponse = BatchPushResponse(listOf(winning())))
        val engine = engine(api, this, tasks, outbox, shadow)

        val summary = engine.push().getOrThrow()

        assertEquals(1, summary.succeeded)
        assertEquals(0, summary.lost)
        val settled = shadow.state(SCOPE.ownerId, SCOPE.profileId, DocType.Task.key, ENTITY_ID.value)!!
        assertTrue(
            settled.confirmedJson.contains(LOSING_TITLE),
            "a won race must still promote its own state",
        )
        assertEquals(LOSING_TITLE, storedTitle(tasks), "a won race must not revert the row")
        assertEquals(4L, settled.serverVersion)
    }

    // ── the wire ────────────────────────────────────────────────────────────────────

    @Test
    fun `the lost flag survives decoding from a server response`() {
        // The original defect at its narrowest: the field arrives and is dropped. The
        // live server emits exactly this object — note it carries neither `serverState`
        // nor `newState`, which is why the resolution reads the shadow instead.
        val json = """
            {"patchId":"calib-2","ok":true,"cached":false,"lost":true,"legacy":false,"newVersion":1}
        """.trimIndent()

        val result = StableJson.decodeFromString<PatchResult>(json)

        assertTrue(result.ok, "the server reports a lost race as ok")
        assertTrue(result.lost, "the lost flag was dropped at the parse boundary")
        assertNull(result.serverState, "nothing behind this field today")
    }

    // ── the failure the revert has to survive ───────────────────────────────────────

    @Test
    fun `a revert that fails leaves the marker, so nothing regenerates the edit`() = runTest {
        // Releasing the marker after a failed revert is the loop. Holding it is not a
        // fix either — the edit stays owned by the shadow and the loss is logged — but
        // it is the state that cannot spin.
        val tasks = FakeTaskRepository()
        val shadow = FakeSyncShadowDao()
        val outbox = FakeSyncOutboxDao()
        raceInProgress(tasks, shadow, outbox)
        val api = FakeSyncApiClient(pushResponse = BatchPushResponse(listOf(losing())))
        val engine = engine(api, this, FailingTaskRepository(), outbox, shadow)

        engine.push()

        assertEquals(
            "p1",
            shadow.state(SCOPE.ownerId, SCOPE.profileId, DocType.Task.key, ENTITY_ID.value)!!.inFlightPatchId,
            "the marker was released although the row was never returned to the server",
        )
        assertTrue(outbox.rows.isEmpty(), "a lost patch must not be re-sent to lose again")
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────

    /** A repository that cannot store the revert, which is the case worth surviving. */
    private class FailingTaskRepository : TaskRepository by FakeTaskRepository() {
        override suspend fun upsert(task: Task): Task = error("disk full")
    }

    /** A real task as a sync entity, so `enqueue` diffs the same document the writer stores. */
    private class SyncableTask(private val task: Task) : SyncableEntity {
        override val syncId: String get() = task.id.value
        override val docType: DocType get() = DocType.Task
        override val syncServerVersion: Long get() = 0
        override val syncHlc: Hlc? get() = null
        override fun toJson(): JsonObject =
            StableJson.encodeToJsonElement(Task.serializer(), task).jsonObject
    }

    private companion object {
        val SCOPE = SyncScope("owner-lost", "profile-1")
        val ENTITY_ID = TaskId.fromString("entity-1")
        const val SERVER_TITLE = "title from the server"
        const val LOSING_TITLE = "title this device lost"
    }
}
