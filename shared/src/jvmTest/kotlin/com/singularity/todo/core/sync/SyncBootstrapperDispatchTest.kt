package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.work.FakeSyncWorkScheduler
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.core.ids.TimeEntryId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.timetracking.domain.TimeEntry
import com.singularity.todo.feature.timetracking.domain.TimeEntryKind
import com.singularity.todo.feature.timetracking.domain.TimeEntrySource
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeTimeTrackingRepository
import com.singularity.todo.test.fakes.testTask
import kotlinx.coroutines.test.TestScope
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Which [DocType]s [SyncBootstrapper] actually wires to a pull handler.
 *
 * ## Why this needs a test
 *
 * The pull loop stops at the first event it cannot apply and does not advance the
 * cursor past it. That is correct — advancing past an unapplied event loses it
 * forever — but it turns a *missing handler* from a silent data-loss bug into a
 * hard stall: every subsequent pull re-requests from the same position,
 * re-receives the same event, and stops there again. Nothing moves, and the
 * symptom is a sync that reports success.
 *
 * So the dispatch table is a load-bearing list, not a registration convenience.
 * It is asserted here so that adding a [DocType] without adding a handler fails
 * with a sentence saying which, instead of producing a device that stopped syncing.
 *
 * ## The asymmetry, stated rather than hidden
 *
 * [DocType.TimeEntry] has both a `DELETED` and a `CREATED`/`UPDATED` branch now, but is
 * absent from [SeedPlanner.SEEDED_TYPES]: tracked time is applied when it arrives and is
 * not uploaded when it is created. Applying a remote document and publishing a local one
 * are separate questions and only the first has been decided — see #177.
 *
 * The distinction is asserted rather than assumed, because the asymmetry is what makes
 * the equality in the first test wrong: a missing handler stalls an account forever,
 * while an unseeded type merely stays on the device.
 */
@Tag("fast")
class SyncBootstrapperDispatchTest {
    /**
     * The engine reads this for every timestamp, and the tests below assert on two of
     * them. Movable rather than fixed so a backoff deferral can be observed expiring
     * without the test waiting in real time.
     */
    private val clock = MutableClock()

    private val log = Logger.withTag("SyncBootstrapperDispatchTest")

    private val timeTracking = FakeTimeTrackingRepository(FakeClock())

    private fun bootstrapped(
        scope: TestScope,
        api: FakeSyncApiClient = FakeSyncApiClient(),
        taskRepo: TaskRepository = FakeTaskRepository(),
        stateRepository: FakeSyncStateRepository = FakeSyncStateRepository(),
        timeTracking: FakeTimeTrackingRepository = this.timeTracking,
    ): SyncEngine {
        val engine = SyncEngine(
            log = log,
            api = api,
            authRepository = FakeSyncAuthRepository(
                Session.SignedIn(UserId.generate(), "t@x.com", "access", "refresh"),
            ),
            outboxDao = FakeSyncOutboxDao(),
            deadLetterDao = FakeSyncDeadLetterDao(),
            idGenerator = SequentialIdGenerator(),
            stateRepository = stateRepository,
            shadowDao = FakeSyncShadowDao(),
            patchBuilder = fakeSyncPatchBuilder(),
            writerProvider = { fakeSyncDocumentWriter() },
            scopeProvider = FakeSyncScopeProvider(SyncScope("owner-1", "profile-1")),
            scheduler = FakeSyncWorkScheduler(),
            clock = clock,
            scope = testScope(scope.backgroundScope),
            crashReporter = NoOpCrashReportingPort(),
        )
        SyncBootstrapper(
            engine = engine,
            writer = fakeSyncDocumentWriter(
                tasks = taskRepo,
                timeTracking = timeTracking,
            ),
        )
        return engine
    }

    /**
     * Every type the client can enqueue has a handler that can apply it.
     *
     * ## What this does not cover
     *
     * The **edit** path. A repository enqueues one changed entity through the same
     * `enqueue` call, and nothing checks *that* type against the table — the call site
     * names an entity, not a type, so it cannot be read without running the repository,
     * and asserting on `enqueue` itself would forbid a capability rather than fix a call
     * site. The seed is the one place that produces every type at once, which is why it
     * is what is checked.
     *
     * So the invariant is real for the seed and unverified for edits. #177 is the
     * argument for closing that by giving the seeded types a single owner; until then
     * the honest statement is that a repository could start enqueuing a type with no
     * handler and the suite would not notice.
     */
    @Test
    fun `the pull dispatch table covers every type that is pushed`() = runTest {
        val engine = bootstrapped(this)

        // Subset, not equality. Equality was the right assertion while the table and
        // the seed named the same five types; #177 made the table wider than the seed,
        // because a handler that exists costs nothing and one that is missing stalls the
        // receiving account forever, while a type that is merely unseeded merely does not
        // upload. The invariant that prevents the stall is "seeded implies appliable",
        // and that is what is asserted here. The reverse direction is a product decision,
        // pinned by its own test below.
        val appliable = engine.handlers.keys
        val missing = SeedPlanner.SEEDED_TYPES - appliable

        assertTrue(
            missing.isEmpty(),
            "seeded but not appliable: $missing. Either register a pull handler, or stop " +
                "enqueueing the type — a device that receives an event it cannot apply " +
                "holds its cursor and never advances again.",
        )
    }

    @Test
    fun `a type with no pull handler is not one the seed can enqueue`() = runTest {
        // The other direction, and the one that actually bit: the seed was uploading
        // time entries while this table could only delete them. Stated on its own so
        // the failure names which side is wrong.
        val unhandled = DocType.entries - bootstrapped(this).handlers.keys

        assertTrue(
            unhandled.intersect(SeedPlanner.SEEDED_TYPES).isEmpty(),
            "seeded but not appliable: ${unhandled.intersect(SeedPlanner.SEEDED_TYPES)}. " +
                "Either register a pull handler, or stop enqueueing the type.",
        )
    }

    @Test
    fun `TimeEntry is appliable, and is still not seeded`() = runTest {
        // #177. The delete half of TimeEntry was registered long before the apply half,
        // and the missing handler was the stall: an incoming time-entry event was
        // Unappliable, the cursor was held back, and every cycle re-received it. The
        // pinned version of this test asserted the handler was absent, and it is now
        // inverted — the handler exists, which is the half that cannot be taken away.
        val engine = bootstrapped(this)

        assertNotNull(
            engine.handlers[DocType.TimeEntry],
            "TimeEntry lost its pull handler. Without one, an event of that type stalls " +
                "the receiving account's cursor permanently.",
        )
    }

    @Test
    fun `TimeEntry is appliable but not seeded, and that is a product decision`() = runTest {
        // Applying a remote entry and uploading a local one are separate questions, and
        // only the first has been answered. `TimeTrackingRepository` modelled tracking as
        // a state machine with no whole-row write, so there was no way to apply one; it
        // now has `upsert` for the sync path, and the seed still does not enqueue the
        // type. So tracked time does not travel between devices today.
        //
        // Adding `DocType.TimeEntry` to `SeedPlanner.SEEDED_TYPES` would make it travel.
        // That is a product decision, not a correctness one, so it is left open here and
        // stated as its own test — when it is taken, this test changes to assert the
        // equality the dispatch table asserts for the other five.
        val engine = bootstrapped(this)

        assertTrue(
            DocType.TimeEntry !in SeedPlanner.SEEDED_TYPES,
            "TimeEntry is now seeded. Tracked time now syncs between devices, which was " +
                "decided rather than left open — update this test and the dispatch-table " +
                "assertion above to say so.",
        )
    }

    @Test
    fun `an event with no document is skipped, not applied`() = runTest {
        // The decision itself, where it is made. `SyncEnginePullTest` covers what the
        // engine does with each outcome; this covers that a payload-less event produces
        // the outcome that lets the feed continue.
        val engine = bootstrapped(this)
        val handler = engine.handlers.getValue(DocType.Task)

        val outcome = handler.apply(
            syncEvent {
                serverLsn = 10
                entityType = DocType.Task
                entityId = "t-1"
                data = null
            },
        )

        assertIs<ApplyOutcome.Skipped>(outcome, "no document means it was never applied")
    }

    @Test
    fun `an event whose payload is not a document is skipped, not applied`() = runTest {
        val engine = bootstrapped(this)
        val handler = engine.handlers.getValue(DocType.Task)

        val outcome = handler.apply(
            syncEvent {
                serverLsn = 10
                entityType = DocType.Task
                entityId = "t-1"
                data = kotlinx.serialization.json.JsonPrimitive("not an object")
            },
        )

        assertIs<ApplyOutcome.Skipped>(outcome)
    }

    @Test
    fun `a time-entry document is applied, not merely routed`() = runTest {
        // The routing assertions above pass the moment a handler is registered, so this
        // one checks the thing that actually matters: the document reaches the repository
        // and is readable afterwards. A handler that returned Applied without writing
        // would satisfy every other test in this class.
        val tracking = FakeTimeTrackingRepository(FakeClock())
        val engine = bootstrapped(this, timeTracking = tracking)
        val handler = engine.handlers.getValue(DocType.TimeEntry)
        val entry = TimeEntry(
            id = TimeEntryId.fromString("te-remote-1"),
            taskId = TaskId.fromString("t-1"),
            userId = UserId("owner-1"),
            startedAt = Instant.fromEpochMilliseconds(1_000),
            endedAt = Instant.fromEpochMilliseconds(2_000),
            kind = TimeEntryKind.Work,
            source = TimeEntrySource.Manual,
            note = "from the server",
            createdAt = Instant.fromEpochMilliseconds(1_000),
            updatedAt = Instant.fromEpochMilliseconds(2_000),
        )

        val outcome = handler.apply(
            syncEvent {
                serverLsn = 11
                entityType = DocType.TimeEntry
                entityId = entry.id.value
                data = StableJson.encodeToString(serializer<TimeEntry>(), entry).let {
                    kotlinx.serialization.json.Json.parseToJsonElement(it)
                }
            },
        )

        assertIs<ApplyOutcome.Applied>(outcome)
        assertEquals("from the server", tracking.getById(entry.id)?.note)
    }

    @Test
    fun `an event of an unregistered type stalls the cursor instead of being skipped`() = runTest {
        // Built WITHOUT the bootstrapper, so the handler map is empty.
        //
        // This used to name `DocType.TimeEntry` as the unregistered type, and #177
        // registered the last one — so the assertion was testing itself to death: there
        // is no longer a `DocType` this device cannot apply. The behaviour is still
        // load-bearing, because the next type added to the enum arrives with an empty
        // handler map until someone registers it, so it is now reached by withholding
        // the registrations rather than by naming a missing one.
        val api = FakeSyncApiClient(
            pullEvents = listOf(
                syncEvent {
                    serverLsn = 10
                    entityType = DocType.Task
                    entityId = "t-1"
                },
            ),
        )
        val engine = SyncEngine(
            log = log,
            api = api,
            authRepository = FakeSyncAuthRepository(
                Session.SignedIn(UserId.generate(), "t@x.com", "access", "refresh"),
            ),
            outboxDao = FakeSyncOutboxDao(),
            deadLetterDao = FakeSyncDeadLetterDao(),
            idGenerator = SequentialIdGenerator(),
            stateRepository = FakeSyncStateRepository(),
            shadowDao = FakeSyncShadowDao(),
            patchBuilder = fakeSyncPatchBuilder(),
            writerProvider = { fakeSyncDocumentWriter() },
            scopeProvider = FakeSyncScopeProvider(SyncScope("owner-1", "profile-1")),
            scheduler = FakeSyncWorkScheduler(),
            clock = clock,
            scope = testScope(backgroundScope),
            crashReporter = NoOpCrashReportingPort(),
        )

        val outcome = engine.syncOnce()

        // Reported as a failure, because a cycle that stopped early is not a cycle that
        // finished. The cursor assertion lives in `SyncEnginePullTest`; what matters
        // here is that the caller is told, rather than seeing a summary that says one
        // event arrived and nothing was wrong.
        val pull = (outcome as SyncOutcome.Completed).pull
        val error = assertIs<AppError.Persistence>(pull.exceptionOrNull())
        assertEquals("sync.pull_stalled", error.code)
        assertIs<SyncEngineStatus.Failure>(engine.status.value, "a stalled pull must not leave the engine idle")
    }

    // ─── A write that does not happen (the S1) ──────────────────────────────────

    /**
     * A repository whose every write fails the way a full disk or a broken constraint
     * fails: by throwing, not by returning a `Result`.
     *
     * That is the real contract. `TaskRepository.upsert` returns `Task` and throws —
     * there is no `Result` to inspect — so the throw *is* the failure signal, and the
     * handler above it is the only place that can classify it.
     */
    private class ThrowingTaskRepository : FakeTaskRepository() {
        override suspend fun upsert(task: Task): Task =
            throw IllegalStateException("disk is full")
    }

    private fun taskDocument(id: String): kotlinx.serialization.json.JsonElement =
        StableJson.encodeToJsonElement(serializer<Task>(), testTask(id = TaskId.fromString(id)))

    /**
     * The classification itself, at the point it is decided.
     *
     * `Conflict` and `Failed` differ only in what the engine does next, and that
     * difference is the whole defect: `Conflict` advances the cursor, `Failed` holds it.
     */
    @Test
    fun `an event whose write throws is failed, not a conflict`() = runTest {
        val engine = bootstrapped(this, taskRepo = ThrowingTaskRepository())
        val handler = engine.handlers.getValue(DocType.Task)

        val outcome = handler.apply(
            syncEvent {
                serverLsn = 10
                entityType = DocType.Task
                entityId = "t-1"
                data = taskDocument("t-1")
            },
        )

        assertIs<ApplyOutcome.Failed>(
            outcome,
            "a write that did not happen might succeed next cycle, so the cursor must " +
                "stay. Conflict means 'applied but a field lost to a newer one' and the " +
                "engine advances past it — which consumed the event and lost the edit.",
        )
    }

    /**
     * The consequence, measured on the cursor rather than on the enum.
     *
     * This is the assertion that would have failed before the fix. `Conflict` moved the
     * cursor to lsn 10, the engine stamped a successful sync, and no later cycle would
     * ever re-request an event the server considered delivered.
     */
    @Test
    fun `an event whose write throws does not move the cursor`() = runTest {
        val api = FakeSyncApiClient(
            pullEvents = listOf(
                syncEvent {
                    serverLsn = 10
                    entityType = DocType.Task
                    entityId = "t-1"
                    data = taskDocument("t-1")
                },
            ),
        )
        val state = FakeSyncStateRepository()
        val engine = bootstrapped(this, api, taskRepo = ThrowingTaskRepository(), stateRepository = state)

        engine.syncOnce()

        assertEquals(
            0L,
            state.get(SyncScope("owner-1", "profile-1")).lastLsn,
            "the cursor moved past an event whose row was never written. The server " +
                "will not send it again, so the edit is gone with no trace.",
        )
    }

    /**
     * The negative control for the new `SerializationException` arm.
     *
     * Bytes that cannot be decoded are the *other* failure: they will arrive
     * identically on every later cycle, so holding the cursor would wedge the account
     * on one undecodable row forever — the outcome `ApplyOutcome.Skipped`'s own KDoc
     * says it exists to prevent. Splitting "unusable" from "unwritten" is only correct
     * if this one still moves.
     */
    @Test
    fun `an event whose payload cannot be decoded is skipped, and does move the cursor`() = runTest {
        val api = FakeSyncApiClient(
            pullEvents = listOf(
                syncEvent {
                    serverLsn = 10
                    entityType = DocType.Task
                    entityId = "t-1"
                    // A document-shaped payload that is not a Task. `ignoreUnknownKeys`
                    // tolerates extra keys, so the failure has to be a type one.
                    data = kotlinx.serialization.json.buildJsonObject {
                        put("id", kotlinx.serialization.json.JsonPrimitive(42))
                        put("title", kotlinx.serialization.json.JsonPrimitive("not a task"))
                    }
                },
            ),
        )
        val state = FakeSyncStateRepository()
        val engine = bootstrapped(this, api, stateRepository = state)

        engine.syncOnce()

        assertEquals(
            10L,
            state.get(SyncScope("owner-1", "profile-1")).lastLsn,
            "an undecodable payload can never apply, so holding the cursor here would " +
                "stop the account syncing anything else, forever, over one bad row.",
        )
    }
}
