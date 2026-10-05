package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.sync.work.FakeSyncWorkScheduler
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTagGroupRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeTimeTrackingRepository
import kotlinx.coroutines.test.TestScope
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
 * [DocType.TimeEntry] has a `DELETED` branch in the bootstrapper and no
 * `CREATED`/`UPDATED` branch, because
 * [com.singularity.todo.feature.timetracking.domain.port.TimeTrackingRepository] has
 * `delete` but no upsert. It is not currently a stall, because nothing enqueues a
 * time entry for push, so the server has no time-entry events to send. The moment
 * that push is wired up, the exclusion assertion below is the one that must change
 * in the same change — which is why it is written down rather than left implicit.
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
            stateRepository = FakeSyncStateRepository(),
            shadowDao = FakeSyncShadowDao(),
            patchBuilder = fakeSyncPatchBuilder(),
            scopeProvider = FakeSyncScopeProvider(SyncScope("owner-1", "profile-1")),
            scheduler = FakeSyncWorkScheduler(),
            clock = clock,
            scope = testScope(scope.backgroundScope),
            crashReporter = NoOpCrashReportingPort(),
        )
        SyncBootstrapper(
            engine = engine,
            taskRepo = FakeTaskRepository(),
            noteRepo = FakeNotesRepository(),
            projectRepo = FakeProjectsRepository(),
            tagRepo = FakeTagsRepository(),
            tagGroupRepo = FakeTagGroupRepository(),
            timeTrackingRepo = timeTracking,
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

        assertEquals(
            SeedPlanner.SEEDED_TYPES,
            engine.handlers.keys,
            "every DocType the client can enqueue needs a pull handler, or the " +
                "receiving account's cursor stalls on the first such event and never " +
                "advances again. A type is only correct on one side of this: " +
                "the seed must not enqueue what the dispatch table cannot apply.",
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
    fun `TimeEntry has no pull handler, and the reason is pinned`() = runTest {
        // Pinned rather than fixed. `TimeTrackingRepository` models tracking as a state
        // machine (`startEntry` / `stopEntry`) and has no upsert, so there is no way to
        // apply a remote document — which is why the table can delete a time entry and
        // not create one.
        //
        // The seed stopped enqueueing them, which is the smaller change: a second device
        // used to receive nothing *and* stall its cursor, so nobody is worse off. If
        // tracked time should sync, this test and the seed are both wrong and change
        // together — see #177 for the product question this defers.
        val engine = bootstrapped(this)

        assertNull(
            engine.handlers[DocType.TimeEntry],
            "TimeEntry has gained a pull handler. Register it here, add DocType.TimeEntry " +
                "to SeedPlanner.SEEDED_TYPES in the same change, and delete this test — " +
                "the two assertions above then carry the invariant.",
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
    fun `an event of an unregistered type stalls the cursor instead of being skipped`() = runTest {
        val api = FakeSyncApiClient(
            pullEvents = listOf(
                syncEvent {
                    serverLsn = 10
                    entityType = DocType.TimeEntry
                    entityId = "te-1"
                },
            ),
        )
        val engine = bootstrapped(this, api)

        val outcome = engine.syncOnce()

        // Reported as a failure, because a cycle that stopped early is not a cycle that
        // finished. The cursor assertion lives in `SyncEnginePullTest`; what matters
        // here is that the caller is told, rather than seeing a summary that says one
        // event arrived and nothing was wrong.
        val pull = (outcome as SyncOutcome.Success).pull
        val error = assertIs<AppError.Persistence>(pull.exceptionOrNull())
        assertEquals("sync.pull_stalled", error.code)
        assertIs<SyncEngineStatus.Failure>(engine.status.value, "a stalled pull must not leave the engine idle")
    }
}
