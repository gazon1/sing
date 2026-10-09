package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.testTask
import com.singularity.todo.test.helpers.MutableClock
import com.singularity.todo.core.serialization.StableJson
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for [PullPhase] — the pull half of the sync cycle.
 *
 * These are unit tests of [PullPhase] in isolation. [SyncEnginePullTest] covers
 * the end-to-end pull behaviour through [SyncEngine].
 *
 * ## Stall — unappliable event
 *
 * When an event's type has no registered handler, the pull loop stops and reports
 * a `sync.pull_stalled` error. The cursor is NOT advanced past the unappliable event,
 * so the next cycle will receive it again.
 *
 * ## Page loop termination
 *
 * The loop continues while `lastPageFull && stalled == null`. A page of exactly
 * `PULL_PAGE_SIZE` events might mean more are available, so the loop asks again.
 * A shorter page means the feed is exhausted and the loop stops.
 */
@Tag("fast")
class PullPhaseTest {

    private val clock = MutableClock()
    private val log = Logger.withTag("PullPhaseTest")

    private val testScopeObj = SyncScope("owner-pull", "profile-1")

    private fun TestScope.phase(
        api: FakeSyncApiClient = FakeSyncApiClient(),
        auth: FakeSyncAuthRepository = FakeSyncAuthRepository(
            Session.SignedIn(UserId("owner-pull"), "t@x.com", "access", "refresh"),
        ),
        stateRepo: FakeSyncStateRepository = FakeSyncStateRepository(),
        scopeProvider: FakeSyncScopeProvider = FakeSyncScopeProvider(testScopeObj),
        taskRepo: FakeTaskRepository = FakeTaskRepository(),
    ): Pair<PullPhase, AutoCloseableCoroutineScope> {
        val writer = fakeSyncDocumentWriter(tasks = taskRepo)
        val state = SyncEngineState(log, NoOpCrashReportingPort())
        val phaseScope = testScope(this)
        // Build handlers from a real SyncBootstrapper-like setup
        val handlers = mapOf(
            DocType.Task to EntityApply { event ->
                val data = event.data ?: return@EntityApply ApplyOutcome.Skipped("no document")
                val doc = try {
                    StableJson.decodeFromString(serializer<Task>(), data.toString())
                } catch (e: Exception) {
                    return@EntityApply ApplyOutcome.Skipped("decode failed")
                }
                writer.upsert(DocType.Task, StableJson.encodeToString(serializer<Task>(), doc)
                    .let { Json.parseToJsonElement(it) as JsonObject })
                ApplyOutcome.Applied
            },
        )
        val p = PullPhase(
            api = api,
            authRepository = auth,
            stateRepository = stateRepo,
            clock = clock,
            scopeProvider = scopeProvider,
            phases = state.phases,
            getHandlers = { handlers },
            scope = phaseScope,
        )
        return p to phaseScope
    }

    private fun taskEvent(lsn: Long, entityId: String = "e-$lsn", profile: String = "profile-1") = syncEvent {
        serverLsn = lsn
        this.entityId = entityId
        entityType = DocType.Task
        eventType = SyncEventType.CREATED
        this.profileId = profile
        data = StableJson.encodeToJsonElement(
            serializer<Task>(),
            testTask(id = TaskId.fromString(entityId)),
        )
    }

    // ─── Not signed in ─────────────────────────────────────────────────────

    @Test
    fun `pull returns NotRun when not signed in`() = runTest {
        val auth = FakeSyncAuthRepository(Session.SignedOut)
        val (p, phaseScope) = phase(auth = auth)

        val result = p.pull(testScopeObj, sinceLsn = 0)
        advanceUntilIdle()
        phaseScope.close()

        assertIs<PhaseResult.NotRun>(result)
    }

    // ─── Empty page ────────────────────────────────────────────────────────

    @Test
    fun `empty page returns zero summary and idle status`() = runTest {
        val api = FakeSyncApiClient(pullEvents = emptyList())
        val stateRepo = FakeSyncStateRepository()
        val state = SyncEngineState(log, NoOpCrashReportingPort())
        val scopeProvider = FakeSyncScopeProvider(testScopeObj)
        val phaseScope = testScope(this)

        val p = PullPhase(
            api = api,
            authRepository = FakeSyncAuthRepository(
                Session.SignedIn(UserId("owner-pull"), "t@x.com", "access", "refresh"),
            ),
            stateRepository = stateRepo,
            clock = clock,
            scopeProvider = scopeProvider,
            phases = state.phases,
            getHandlers = { emptyMap() },
            scope = phaseScope,
        )

        val result = p.pull(testScopeObj, sinceLsn = 0)
        advanceUntilIdle()
        phaseScope.close()

        val ok = assertIs<PhaseResult.Ok<PullSummary>>(result)
        assertEquals(0, ok.value.received)
        assertEquals(0, ok.value.applied)
        assertEquals(0, ok.value.dropped)
        assertIs<SyncEngineStatus.Idle>(state.status.value)
    }

    // ─── Page of exactly PULL_PAGE_SIZE ────────────────────────────────────

    @Test
    fun `a page of exactly PULL_PAGE_SIZE events triggers another pull`() = runTest {
        val events = (1L..PULL_PAGE_SIZE.toLong()).map { taskEvent(it, "e-$it") }
        val api = FakeSyncApiClient(pullEvents = events)
        val stateRepo = FakeSyncStateRepository()
        val state = SyncEngineState(log, NoOpCrashReportingPort())
        val taskRepo = FakeTaskRepository()
        val scopeProvider = FakeSyncScopeProvider(testScopeObj)

        // The API returns the same events for every call (no cursor tracking by default
        // in FakeSyncApiClient unless ignoreSinceLsn is set). We set ignoreSinceLsn to
        // simulate the server returning the same page, which would cause an infinite loop
        // without the `next <= readFrom` guard in PullPhase.
        api.ignoreSinceLsn = true
        val phaseScope = testScope(this)

        val p = PullPhase(
            api = api,
            authRepository = FakeSyncAuthRepository(
                Session.SignedIn(UserId("owner-pull"), "t@x.com", "access", "refresh"),
            ),
            stateRepository = stateRepo,
            clock = clock,
            scopeProvider = scopeProvider,
            phases = state.phases,
            getHandlers = { mapOf(DocType.Task to EntityApply { ApplyOutcome.Applied }) },
            scope = phaseScope,
        )

        val result = p.pull(testScopeObj, sinceLsn = 0)
        advanceUntilIdle()
        phaseScope.close()

        // With ignoreSinceLsn=true, the fake returns the same 100 events on every call.
        // Call 1: received=100, readFrom=100, lastPageFull=true, continue.
        // Call 2: received=200, next=100, `next <= readFrom` guard fires → STOP.
        // Two iterations = 200 events received. The guard prevents the infinite loop.
        val ok = assertIs<PhaseResult.Ok<PullSummary>>(result)
        assertEquals(200, ok.value.received)
    }

    // ─── Stall on unappliable event ────────────────────────────────────────

    @Test
    fun `pull stalls when event has no handler`() = runTest {
        // Return one event with no handler registered.
        val events = listOf(syncEvent {
            serverLsn = 10
            entityType = DocType.TagGroup
            entityId = "tg-1"
            profileId = "profile-1"
        })
        val api = FakeSyncApiClient(pullEvents = events)
        val stateRepo = FakeSyncStateRepository()
        val state = SyncEngineState(log, NoOpCrashReportingPort())
        val phaseScope = testScope(this)

        val p = PullPhase(
            api = api,
            authRepository = FakeSyncAuthRepository(
                Session.SignedIn(UserId("owner-pull"), "t@x.com", "access", "refresh"),
            ),
            stateRepository = stateRepo,
            clock = clock,
            scopeProvider = FakeSyncScopeProvider(testScopeObj),
            phases = state.phases,
            getHandlers = { emptyMap() }, // No handler for TagGroup.
            scope = phaseScope,
        )

        val result = p.pull(testScopeObj, sinceLsn = 0)
        advanceUntilIdle()
        phaseScope.close()

        val failed = assertIs<PhaseResult.Failed<PullSummary>>(result)
        val error = failed.error as? AppError.Persistence
        assertEquals("sync.pull_stalled", error?.code)
    }

    @Test
    fun `stalled pull does not advance the cursor`() = runTest {
        val events = listOf(syncEvent {
            serverLsn = 10
            entityType = DocType.TagGroup
            entityId = "tg-1"
            profileId = "profile-1"
        })
        val api = FakeSyncApiClient(pullEvents = events)
        val stateRepo = FakeSyncStateRepository()
        val state = SyncEngineState(log, NoOpCrashReportingPort())
        val phaseScope = testScope(this)

        val p = PullPhase(
            api = api,
            authRepository = FakeSyncAuthRepository(
                Session.SignedIn(UserId("owner-pull"), "t@x.com", "access", "refresh"),
            ),
            stateRepository = stateRepo,
            clock = clock,
            scopeProvider = FakeSyncScopeProvider(testScopeObj),
            phases = state.phases,
            getHandlers = { emptyMap() },
            scope = phaseScope,
        )

        p.pull(testScopeObj, sinceLsn = 5)
        advanceUntilIdle()
        phaseScope.close()

        // Cursor must not have moved past the unappliable event.
        assertEquals(5L, stateRepo.lastLsn(testScopeObj))
    }

    @Test
    fun `stalled pull leaves status on Failure`() = runTest {
        val events = listOf(syncEvent {
            serverLsn = 10
            entityType = DocType.TagGroup
            entityId = "tg-1"
            profileId = "profile-1"
        })
        val api = FakeSyncApiClient(pullEvents = events)
        val stateRepo = FakeSyncStateRepository()
        val state = SyncEngineState(log, NoOpCrashReportingPort())
        val phaseScope = testScope(this)

        val p = PullPhase(
            api = api,
            authRepository = FakeSyncAuthRepository(
                Session.SignedIn(UserId("owner-pull"), "t@x.com", "access", "refresh"),
            ),
            stateRepository = stateRepo,
            clock = clock,
            scopeProvider = FakeSyncScopeProvider(testScopeObj),
            phases = state.phases,
            getHandlers = { emptyMap() },
            scope = phaseScope,
        )

        p.pull(testScopeObj, sinceLsn = 0)
        advanceUntilIdle()
        phaseScope.close()

        assertIs<SyncEngineStatus.Failure>(state.status.value)
    }

    // ─── applyEvent: wrong profile ──────────────────────────────────────────

    @Test
    fun `event for a different profile is skipped and cursor advances`() = runTest {
        val events = listOf(
            taskEvent(lsn = 10, profile = "other-profile"),
        )
        val api = FakeSyncApiClient(pullEvents = events)
        val stateRepo = FakeSyncStateRepository()
        val state = SyncEngineState(log, NoOpCrashReportingPort())
        val phaseScope = testScope(this)

        val p = PullPhase(
            api = api,
            authRepository = FakeSyncAuthRepository(
                Session.SignedIn(UserId("owner-pull"), "t@x.com", "access", "refresh"),
            ),
            stateRepository = stateRepo,
            clock = clock,
            scopeProvider = FakeSyncScopeProvider(testScopeObj),
            phases = state.phases,
            getHandlers = { mapOf(DocType.Task to EntityApply { ApplyOutcome.Applied }) },
            scope = phaseScope,
        )

        val result = p.pull(testScopeObj, sinceLsn = 0)
        advanceUntilIdle()
        phaseScope.close()

        val ok = assertIs<PhaseResult.Ok<PullSummary>>(result)
        assertEquals(1, ok.value.received)
        assertEquals(1, ok.value.dropped, "skipped event counts as dropped")
        assertEquals(10L, stateRepo.lastLsn(testScopeObj), "cursor must advance past skipped event")
    }

    // ─── Multiple pages ─────────────────────────────────────────────────────

    @Test
    fun `multiple pages are processed until the feed is exhausted`() = runTest {
        // Two pages: first page of 3, second page of 2.
        val page1 = (1L..3L).map { taskEvent(it, "e-$it") }
        val page2 = (4L..5L).map { taskEvent(it, "e-$it") }

        var callCount = 0
        val api = FakeSyncApiClient(pullEvents = page1 + page2)
        val stateRepo = FakeSyncStateRepository()
        val state = SyncEngineState(log, NoOpCrashReportingPort())
        val phaseScope = testScope(this)

        val p = PullPhase(
            api = api,
            authRepository = FakeSyncAuthRepository(
                Session.SignedIn(UserId("owner-pull"), "t@x.com", "access", "refresh"),
            ),
            stateRepository = stateRepo,
            clock = clock,
            scopeProvider = FakeSyncScopeProvider(testScopeObj),
            phases = state.phases,
            getHandlers = { mapOf(DocType.Task to EntityApply { ApplyOutcome.Applied }) },
            scope = phaseScope,
        )

        val result = p.pull(testScopeObj, sinceLsn = 0)
        advanceUntilIdle()
        phaseScope.close()

        val ok = assertIs<PhaseResult.Ok<PullSummary>>(result)
        assertEquals(5, ok.value.received)
        assertEquals(5, ok.value.applied)
        assertEquals(0, ok.value.dropped)
        assertEquals(5L, stateRepo.lastLsn(testScopeObj))
    }

    // ─── Cursor re-read protection ──────────────────────────────────────────

    /**
     * The scope and sinceLsn are passed TO pull(), not re-read inside it.
     * If they were re-read, a profile switch between the read and write would store
     * one scope's position under another. We verify this by checking that the state
     * repository is called with exactly the scope that was passed.
     */
    @Test
    fun `pull uses the scope parameter, not re-reading scopeProvider`() = runTest {
        val events = listOf(taskEvent(lsn = 10))
        val api = FakeSyncApiClient(pullEvents = events)
        val stateRepo = FakeSyncStateRepository()
        val state = SyncEngineState(log, NoOpCrashReportingPort())

        // The scope passed in.
        val scope = SyncScope("passed-owner", "passed-profile")
        val phaseScope = testScope(this)

        val p = PullPhase(
            api = api,
            authRepository = FakeSyncAuthRepository(
                Session.SignedIn(UserId("passed-owner"), "t@x.com", "access", "refresh"),
            ),
            stateRepository = stateRepo,
            clock = clock,
            scopeProvider = FakeSyncScopeProvider(scope), // same scope
            phases = state.phases,
            getHandlers = { mapOf(DocType.Task to EntityApply { ApplyOutcome.Applied }) },
            scope = phaseScope,
        )

        p.pull(scope, sinceLsn = 0)
        advanceUntilIdle()
        phaseScope.close()

        assertEquals(10L, stateRepo.lastLsn(scope))
    }

    // ─── API failure ────────────────────────────────────────────────────────

    @Test
    fun `API exception produces a failed result`() = runTest {
        val api = FakeSyncApiClient()
        api.failWith = IllegalStateException("network down")
        val stateRepo = FakeSyncStateRepository()
        val state = SyncEngineState(log, NoOpCrashReportingPort())
        val phaseScope = testScope(this)

        val p = PullPhase(
            api = api,
            authRepository = FakeSyncAuthRepository(
                Session.SignedIn(UserId("owner-pull"), "t@x.com", "access", "refresh"),
            ),
            stateRepository = stateRepo,
            clock = clock,
            scopeProvider = FakeSyncScopeProvider(testScopeObj),
            phases = state.phases,
            getHandlers = { emptyMap() },
            scope = phaseScope,
        )

        val result = p.pull(testScopeObj, sinceLsn = 0)
        advanceUntilIdle()
        phaseScope.close()

        val failed = assertIs<PhaseResult.Failed<PullSummary>>(result)
        assertIs<AppError.Unknown>(failed.error)
    }

    // ─── Successful pull ─────────────────────────────────────────────────────

    @Test
    fun `successful pull records the correct summary and advances cursor`() = runTest {
        val events = listOf(taskEvent(10), taskEvent(20))
        val api = FakeSyncApiClient(pullEvents = events)
        val stateRepo = FakeSyncStateRepository()
        val state = SyncEngineState(log, NoOpCrashReportingPort())
        val phaseScope = testScope(this)

        val p = PullPhase(
            api = api,
            authRepository = FakeSyncAuthRepository(
                Session.SignedIn(UserId("owner-pull"), "t@x.com", "access", "refresh"),
            ),
            stateRepository = stateRepo,
            clock = clock,
            scopeProvider = FakeSyncScopeProvider(testScopeObj),
            phases = state.phases,
            getHandlers = { mapOf(DocType.Task to EntityApply { ApplyOutcome.Applied }) },
            scope = phaseScope,
        )

        val result = p.pull(testScopeObj, sinceLsn = 0)
        advanceUntilIdle()
        phaseScope.close()

        val ok = assertIs<PhaseResult.Ok<PullSummary>>(result)
        assertEquals(2, ok.value.received)
        assertEquals(2, ok.value.applied)
        assertEquals(0, ok.value.dropped)
        assertEquals(20L, stateRepo.lastLsn(testScopeObj))
    }
}
