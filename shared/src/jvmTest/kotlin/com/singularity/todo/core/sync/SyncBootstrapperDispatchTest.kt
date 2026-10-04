package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.testScope
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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
            scopeProvider = FakeSyncScopeProvider(SyncScope("owner-1", "profile-1")),
            scheduler = FakeSyncWorkScheduler(),
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

    @Test
    fun `the pull dispatch table covers every type that is pushed`() = runTest {
        val engine = bootstrapped(this)

        // The types that have a push call site. Each one that can arrive from the
        // server needs a handler, or the cursor stalls on it.
        val pushed = setOf(DocType.Task, DocType.Note, DocType.Project, DocType.Tag, DocType.TagGroup)

        assertEquals(
            pushed,
            engine.handlers.keys,
            "every pushed DocType needs a pull handler",
        )
    }

    @Test
    fun `TimeEntry has no pull handler, and the reason is pinned`() = runTest {
        val engine = bootstrapped(this)

        assertNull(
            engine.handlers[DocType.TimeEntry],
            "TimeEntry cannot be upserted from a remote event. When a TimeEntry push is " +
                "wired up, register a handler here in the same change — otherwise the " +
                "pull loop stalls on the first time-entry event and never advances.",
        )
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
        val pull = (outcome as SyncOutcome.Success).pull.getOrThrow()

        assertEquals(1, pull.received)
        assertEquals(0, pull.applied, "nothing may be applied for a type with no handler")
        assertEquals(1, pull.dropped, "the unappliable event must be reported, not hidden")
    }
}
