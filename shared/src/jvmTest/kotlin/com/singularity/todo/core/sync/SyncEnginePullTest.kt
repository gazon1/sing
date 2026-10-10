@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.sync.work.FakeSyncWorkScheduler
import com.singularity.todo.core.sync.work.FakeHlcFactory
import kotlinx.coroutines.test.TestScope
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pull behaviour of [SyncEngine] — and the first tests this class has ever had.
 *
 * ## The defect
 *
 * `pull()` computed `maxLsn = maxOf(maxLsn, event.serverLsn)` *before* looking up a
 * handler, and skipped the event with `?: return@forEach` when no handler was
 * registered. So an event of a type this client cannot handle advanced the download
 * cursor and was never fetched again. The server considered it delivered, the client
 * considered the pull done, and the change was gone — reported as "received 3,
 * applied 2", which reads like a conflict and is not one.
 *
 * REQ-OS-007: an unappliable event must not advance the cursor, and the number of
 * such events must be reported.
 */
@Tag("fast")
class SyncEnginePullTest {
    /**
     * The engine reads this for every timestamp, and the tests below assert on two of
     * them. Movable rather than fixed so a backoff deferral can be observed expiring
     * without the test waiting in real time.
     */
    private val clock = MutableClock()

    private val log = Logger.withTag("SyncEnginePullTest")

    private val profileId = "profile-1"

    /**
     * A scope that matches the session the fake auth repository reports.
     *
     * Derived rather than hard-coded: the engine looks the cursor up by
     * `(owner, profile)`, so a test that seeded a cursor under a different owner
     * would silently assert against a row the engine never reads — and pass.
     */
    private fun scopeFor(auth: FakeSyncAuthRepository): SyncScope {
        val session = auth.currentSession.value as Session.SignedIn
        return SyncScope(ownerId = session.userId.value, profileId = profileId)
    }

    private fun engine(
        api: FakeSyncApiClient,
        scope: TestScope,
        stateRepository: FakeSyncStateRepository = FakeSyncStateRepository(),
        auth: FakeSyncAuthRepository = FakeSyncAuthRepository(signedIn()),
        scopeProvider: FakeSyncScopeProvider = FakeSyncScopeProvider(scopeFor(auth)),
    ) = SyncEngine(
        log = log,
        api = api,
        authRepository = auth,
        outboxDao = FakeSyncOutboxDao(),
        deadLetterDao = FakeSyncDeadLetterDao(),
        idGenerator = SequentialIdGenerator(),
        stateRepository = stateRepository,
        scopeProvider = scopeProvider,
        shadowDao = FakeSyncShadowDao(),
        patchBuilder = fakeSyncPatchBuilder(),
        writerProvider = { fakeSyncDocumentWriter() },
        scheduler = FakeSyncWorkScheduler(),
        // `backgroundScope`, not the test scope. The engine's init collects the auth
        // session forever, so a scope parented to the test's own job would leave an
        // active child at the end of the test body — which `runTest` reports as
        // UncompletedCoroutinesError after a full minute of waiting.
        clock = clock,
        scope = testScope(scope.backgroundScope),
        crashReporter = NoOpCrashReportingPort(),
            hlcFactory = FakeHlcFactory(),
    )

    private fun signedIn() = Session.SignedIn(UserId.generate(), "t@x.com", "access", "refresh")

    /**
     * A task event that carries a document.
     *
     * It has to. The builder defaulted `data` to null, so every event in this suite was
     * payload-less — and a payload-less event is one no real handler can apply. A test
     * that then asserted "the event was applied" was asserting against a shape the
     * server never sends.
     */
    private fun taskEvent(lsn: Long, profile: String = "") = syncEvent {
        serverLsn = lsn
        entityType = DocType.Task
        entityId = "task-$lsn"
        profileId = profile
        data = jsonObject("id" to "task-$lsn")
    }

    private fun event(lsn: Long, type: DocType) = syncEvent {
        serverLsn = lsn
        entityType = type
        entityId = "e-$lsn"
    }

    private fun eventWithoutData(lsn: Long) = syncEvent {
        serverLsn = lsn
        entityType = DocType.Task
        entityId = "e-$lsn"
        data = null
    }

    private fun recordApplied(into: MutableList<Long>) = EntityApply { event ->
        into += event.serverLsn
        ApplyOutcome.Applied
    }

    /**
     * A handler that behaves like the real ones about payload: no document means it
     * could not be applied and never will be, so it is skipped rather than applied.
     *
     * The stub above records every event it is handed, which is the wrong shape for
     * these cases — it said `Applied` for an event with nothing in it, and the pull
     * test then looked like it was proving the engine steps over one. The decision to
     * return `Skipped` belongs to the bootstrapper and is tested there; what is tested
     * here is what the engine *does* with each outcome.
     */
    private fun recordHonestApplication(into: MutableList<Long>) = EntityApply { event ->
        if (event.data == null) {
            ApplyOutcome.Skipped("the event carries no document")
        } else {
            into += event.serverLsn
            ApplyOutcome.Applied
        }
    }

    // ── #176: a cycle drains the feed, or says it could not ───────────────────

    @Test
    fun `a backlog larger than one page is drained in the same cycle`() = runTest {
        // The defect: one request per cycle and no loop. An account with 250 pending
        // changes got 50 of them, reported "synced", and left the other 200 for a cycle
        // that would only run if something else triggered it.
        val api = FakeSyncApiClient(pullEvents = (1..250).map { taskEvent(it.toLong() * 10) })
        val auth = FakeSyncAuthRepository(signedIn())
        val state = FakeSyncStateRepository()
        val syncScope = scopeFor(auth)
        val applied = mutableListOf<Long>()
        val engine = engine(api, this, stateRepository = state, auth = auth)
        engine.registerHandler(DocType.Task, recordApplied(applied))

        val pull = (engine.syncOnce() as SyncOutcome.Completed).pull.getOrThrow()

        assertEquals(250, applied.size, "the cycle must not stop at the end of the first page")
        assertEquals(250, pull.received)
        assertEquals(250, pull.applied)
        // And the stored cursor is the end of the last page, not the end of the first.
        assertEquals(2500L, state.lastLsn(syncScope))
    }

    @Test
    fun `a backlog that fits one page asks exactly once`() = runTest {
        // The other side of the loop: an account with nothing to do must not pay for a
        // second round trip, or every idle sync becomes two requests forever.
        val api = FakeSyncApiClient(pullEvents = (1..10).map { taskEvent(it.toLong()) })
        val auth = FakeSyncAuthRepository(signedIn())
        val engine = engine(api, this, stateRepository = FakeSyncStateRepository(), auth = auth)
        engine.registerHandler(DocType.Task, recordApplied(mutableListOf()))

        (engine.syncOnce() as SyncOutcome.Completed).pull.getOrThrow()

        assertEquals(1, api.pullCalls.size, "a short page is the end of the feed; asking again is waste")
    }

    @Test
    fun `a page that is exactly full is followed by one more request, and the empty answer is the end`() = runTest {
        val api = FakeSyncApiClient(
            pullEvents = (1..PULL_PAGE_SIZE).map { taskEvent(it.toLong()) },
        )
        val auth = FakeSyncAuthRepository(signedIn())
        val engine = engine(api, this, stateRepository = FakeSyncStateRepository(), auth = auth)
        engine.registerHandler(DocType.Task, recordApplied(mutableListOf()))

        (engine.syncOnce() as SyncOutcome.Completed).pull.getOrThrow()

        // A full page is not proof that more exists, and it is not proof that it does
        // not. The only way to know is to ask, and the empty answer is the proof.
        assertEquals(2, api.pullCalls.size)
        assertEquals(
            PULL_PAGE_SIZE.toLong(),
            api.pullCalls.last().second,
            "the second request must resume after the last event of the first page",
        )
    }

    @Test
    fun `a feed that never advances stops instead of asking for the same page forever`() = runTest {
        // A server that ignores the position and answers with the same page is the one
        // failure mode a loop adds that a single request could not have: without the
        // guard this is an infinite loop, which in a sync cycle is a hang that looks
        // like a busy app.
        val stuck = FakeSyncApiClient(
            pullEvents = listOf(taskEvent(1), taskEvent(2)),
        ).apply { ignoreSinceLsn = true }
        val auth = FakeSyncAuthRepository(signedIn())
        val engine = engine(stuck, this, stateRepository = FakeSyncStateRepository(), auth = auth)
        engine.registerHandler(DocType.Task, recordApplied(mutableListOf()))

        val outcome = engine.syncOnce()

        assertTrue(
            (outcome as SyncOutcome.Completed).pull.isSuccess,
            "a feed that will not advance is the server's problem to report, not a " +
                "reason to fail the user's cycle: $outcome",
        )
    }

    // ── #175: an event that was not applied must not be reported as applied ──
    //
    // Both of these were `ApplyOutcome.Applied`. That one value is what made the loss
    // silent: the server considered the event delivered, the client considered it done,
    // and the row was never written again — with a pull summary saying it had arrived.

    @Test
    fun `an event with no payload is dropped and the cursor moves past it`() = runTest {
        val api = FakeSyncApiClient(
            pullEvents = listOf(taskEvent(10), eventWithoutData(20), taskEvent(30)),
        )
        val auth = FakeSyncAuthRepository(signedIn())
        val state = FakeSyncStateRepository()
        val syncScope = scopeFor(auth)
        val applied = mutableListOf<Long>()
        val engine = engine(api, this, stateRepository = state, auth = auth)
        engine.registerHandler(DocType.Task, recordHonestApplication(applied))

        val pull = (engine.syncOnce() as SyncOutcome.Completed).pull.getOrThrow()

        // 30, not 20: an unusable event must not block the feed, or one bad row ends
        // this account's sync for good. Reaching 30 is the assertion — it says the event
        // at 20 was stepped over rather than stalled on.
        assertEquals(30L, state.lastLsn(syncScope), "an unusable event must not block the feed")
        assertEquals(listOf(10L, 30L), applied, "lsn 20 carried no document, so nothing applied it")
        // And it is counted as dropped, not applied: the difference is the whole point.
        assertEquals(1, pull.dropped, "an event that was never applied must not be counted as applied")
        assertEquals(2, pull.applied)
    }

    @Test
    fun `an event with no payload is not a failed cycle`() = runTest {
        // Distinct from the unhandled-*type* case, which does stall. The difference is
        // whether a retry could help: the same bytes will arrive again, so failing the
        // cycle would retry a payload that is not going to improve and would never let
        // the account finish a sync.
        val api = FakeSyncApiClient(pullEvents = listOf(eventWithoutData(10)))
        val auth = FakeSyncAuthRepository(signedIn())
        val engine = engine(api, this, stateRepository = FakeSyncStateRepository(), auth = auth)
        engine.registerHandler(DocType.Task, recordApplied(mutableListOf()))

        val outcome = engine.syncOnce()

        assertTrue(
            (outcome as SyncOutcome.Completed).pull.isSuccess,
            "an unusable payload is skipped, not treated as a failure: $outcome",
        )
        assertIs<SyncEngineStatus.Idle>(engine.status.value)
    }

    @Test
    fun `a delete that did not happen leaves the cursor where it was`() = runTest {
        // The server's delete is the only event that would ever remove the row, so
        // advancing past it strands the local copy with nothing left to say so.
        val api = FakeSyncApiClient(
            pullEvents = listOf(
                syncEvent {
                    serverLsn = 10
                    entityType = DocType.Task
                    entityId = "t-1"
                    eventType = SyncEventType.DELETED
                },
            ),
        )
        val auth = FakeSyncAuthRepository(signedIn())
        val state = FakeSyncStateRepository()
        val syncScope = scopeFor(auth)
        val engine = engine(api, this, stateRepository = state, auth = auth)
        engine.registerHandler(DocType.Task) { ApplyOutcome.Failed("the delete did not happen") }

        val pull = (engine.syncOnce() as SyncOutcome.Completed).pull

        assertEquals(0L, state.lastLsn(syncScope), "a delete that did not happen must be delivered again")
        assertEquals("sync.pull_stalled", assertIs<AppError.Persistence>(pull.exceptionOrNull()).code)
    }

    @Test
    fun `an event whose type has no handler does not advance the cursor`() = runTest {
        val api = FakeSyncApiClient(
            pullEvents = listOf(
                taskEvent(10),
                event(20, DocType.Note),
                event(30, DocType.Tag),
            ),
        )
        val auth = FakeSyncAuthRepository(signedIn())
        val state = FakeSyncStateRepository()
        val syncScope = scopeFor(auth)
        val applied = mutableListOf<Long>()
        val engine = engine(api, this, stateRepository = state, auth = auth)
        // Only DocType.Task is registered. Note and Tag are unknown to this client.
        engine.registerHandler(DocType.Task, recordApplied(applied))

        val outcome = engine.syncOnce()

        assertEquals(listOf(10L), applied, "only the task event should be applied")
        // 10, not 0: the cursor is the last position that was *applied*. lsn 20 is not
        // consumed, so the next pull asks from 10 and receives it again. The original
        // bug stored 20 here, and the event was never seen again.
        assertEquals(10L, state.lastLsn(syncScope), "the cursor must stop before the unknown type")

        // The cycle now reports the stall instead of a partial page as a success. It
        // used to return `Success` here and stamp "last synced", which is what made a
        // permanently stalled account look healthy.
        val pull = (outcome as SyncOutcome.Completed).pull
        val error = assertIs<AppError.Persistence>(pull.exceptionOrNull())
        assertEquals("sync.pull_stalled", error.code)
        assertTrue(
            error.message.orEmpty().contains("20"),
            "the message has to name the position the device is stuck at: ${error.message}",
        )
    }

    @Test
    fun `events after an unappliable one are not consumed by the same cycle`() = runTest {
        val api = FakeSyncApiClient(
            pullEvents = listOf(
                taskEvent(10),
                event(20, DocType.TimeEntry),
                taskEvent(30),
            ),
        )
        val auth = FakeSyncAuthRepository(signedIn())
        val state = FakeSyncStateRepository()
        val syncScope = scopeFor(auth)
        val applied = mutableListOf<Long>()
        val engine = engine(api, this, stateRepository = state, auth = auth)
        engine.registerHandler(DocType.Task, recordApplied(applied))

        engine.syncOnce()

        // lsn 30 is a type this client CAN apply, but it arrives after one it cannot.
        // Stopping the loop leaves it for the next cycle rather than consuming it.
        assertEquals(listOf(10L), applied)
        assertEquals(10L, state.lastLsn(syncScope), "lsn 30 must not be consumed by this cycle")
    }

    @Test
    fun `the cursor advances past events that were applied`() = runTest {
        val api = FakeSyncApiClient(pullEvents = listOf(taskEvent(10), taskEvent(20)))
        val auth = FakeSyncAuthRepository(signedIn())
        val state = FakeSyncStateRepository()
        val syncScope = scopeFor(auth)
        val applied = mutableListOf<Long>()
        val engine = engine(api, this, stateRepository = state, auth = auth)
        engine.registerHandler(DocType.Task, recordApplied(applied))

        val outcome = engine.syncOnce()
        val pull = (outcome as SyncOutcome.Completed).pull.getOrThrow()

        assertEquals(listOf(10L, 20L), applied)
        assertEquals(20L, state.lastLsn(syncScope))
        assertEquals(0, pull.dropped)
        assertEquals(2, pull.applied)
    }

    @Test
    fun `the next pull resumes from the applied position, not the end of the feed`() = runTest {
        val api = FakeSyncApiClient(
            pullEvents = listOf(
                taskEvent(10),
                event(20, DocType.Note),
            ),
        )
        val auth = FakeSyncAuthRepository(signedIn())
        val state = FakeSyncStateRepository()
        val syncScope = scopeFor(auth)
        val engine = engine(api, this, stateRepository = state, auth = auth)
        engine.registerHandler(DocType.Task, recordApplied(mutableListOf()))

        engine.syncOnce()
        engine.syncOnce()

        // The second pull asks from 10, not from 20. Asking from 20 is the original
        // bug: the note is still unapplied, and asking from 20 discards it silently.
        assertEquals(listOf(0L, 10L), api.pullCalls.map { it.second })
    }

    /**
     * A write that did not happen stops the cycle, and the cursor stays put.
     *
     * This used to be `a conflict is counted but still advances the cursor`, and it
     * registered a handler returning `ApplyOutcome.Conflict` by hand. That arm no longer
     * exists — see [ApplyOutcomeArmsTest] for why it could only ever have been produced by
     * a misclassification — and the hand-registered handler was the only thing keeping it
     * alive: a test can construct an outcome the engine cannot reach, which makes an
     * unreachable arm look reachable from the suite.
     *
     * The nearest reachable neighbour is `Failed`, which is what a write that did not happen
     * now produces, and its cursor behaviour is the opposite of the old test's: the page
     * stops there and the whole pull is reported as a failure rather than a partial page
     * reported as success (#175). So the cursor must not move, and the caller has to be
     * told.
     */
    @Test
    fun `a failed write stops the cycle with the cursor still where it was`() = runTest {
        val api = FakeSyncApiClient(pullEvents = listOf(taskEvent(10), taskEvent(20)))
        val auth = FakeSyncAuthRepository(signedIn())
        val state = FakeSyncStateRepository()
        val syncScope = scopeFor(auth)
        val engine = engine(api, this, stateRepository = state, auth = auth)
        engine.registerHandler(DocType.Task) { ApplyOutcome.Failed("the write did not happen") }

        val outcome = engine.syncOnce()
        val pull = (outcome as SyncOutcome.Completed).pull

        assertEquals(
            0L,
            state.lastLsn(syncScope),
            "the cursor must not move past an event whose row was never written — the " +
                "server will not send it again, so moving here loses the change outright",
        )
        val error = pull.exceptionOrNull()
        assertTrue(
            error is AppError.Persistence && error.code == "sync.pull_stalled",
            "a cycle that stopped early is not a cycle that finished: $error",
        )
    }

    @Test
    fun `no scope means the cycle does not run at all`() = runTest {
        val api = FakeSyncApiClient(pullEvents = listOf(taskEvent(10)))
        val engine = engine(
            api = api,
            scope = this,
            scopeProvider = FakeSyncScopeProvider(null),
        )

        val outcome = engine.syncOnce()

        // Skipped, not Success-with-zero-events. The two are indistinguishable in a
        // summary and completely different to a caller: the first means "there was
        // nothing to sync", the second means "sync ran and found nothing new".
        assertTrue(outcome is SyncOutcome.NothingToDo, "without a scope there is nothing to cycle")
        assertTrue(api.pullCalls.isEmpty(), "a scopeless client must not hit the server")
    }

    @Test
    fun `a signed-out client pulls nothing`() = runTest {
        val api = FakeSyncApiClient(pullEvents = listOf(taskEvent(10)))
        // A scope that is still around after the session goes away — the window a
        // sign-out and a scope change do not close atomically. The pull's own session
        // check is what protects the server call here.
        val engine = engine(
            api = api,
            scope = this,
            auth = FakeSyncAuthRepository(Session.SignedOut),
            scopeProvider = FakeSyncScopeProvider(SyncScope("owner-1", profileId)),
        )

        val outcome = engine.syncOnce()
        val pull = (outcome as SyncOutcome.Completed).pull.getOrThrow()

        assertEquals(0, pull.received)
        assertTrue(api.pullCalls.isEmpty(), "a signed-out client must not hit the server")
    }

    // ─── Other profiles ───────────────────────────────────────────────────────
    //
    // The event log is per *owner*, so a client's feed interleaves every profile of
    // the account. Treating another profile's event as "not applicable" — which is
    // what the unhandled-type branch does — would freeze the cursor at the first one
    // and the account would never sync again. So it is skipped, the cursor advances,
    // and it is counted.

    @Test
    fun `another profile's event is skipped and the cursor moves past it`() = runTest {
        val api = FakeSyncApiClient(
            pullEvents = listOf(
                taskEvent(10, profile = "other"),
                taskEvent(20),
            ),
        )
        val auth = FakeSyncAuthRepository(signedIn())
        val state = FakeSyncStateRepository()
        val syncScope = scopeFor(auth)
        val applied = mutableListOf<Long>()
        val engine = engine(api, this, stateRepository = state, auth = auth)
        engine.registerHandler(DocType.Task, recordApplied(applied))

        val outcome = engine.syncOnce()
        val pull = (outcome as SyncOutcome.Completed).pull.getOrThrow()

        assertEquals(listOf(20L), applied, "only this profile's event may be applied")
        assertEquals(20L, state.lastLsn(syncScope), "the cursor must move past the other profile's event")
        assertEquals(1, pull.dropped, "it is dropped, and the count says so")
    }

    @Test
    fun `an event with no profile applies to the pulling scope`() = runTest {
        // An event from a server predating the profile dimension. Applying it is what
        // keeps an old server usable; the risk is a missing dimension, never another
        // profile's data, because an event that names a profile is never applied to a
        // different one.
        val api = FakeSyncApiClient(pullEvents = listOf(taskEvent(10)))
        val auth = FakeSyncAuthRepository(signedIn())
        val state = FakeSyncStateRepository()
        val applied = mutableListOf<Long>()
        val engine = engine(api, this, stateRepository = state, auth = auth)
        engine.registerHandler(DocType.Task, recordApplied(applied))

        engine.syncOnce()

        assertEquals(listOf(10L), applied)
        assertEquals(10L, state.lastLsn(scopeFor(auth)))
    }

    @Test
    fun `a transport failure is reported, not swallowed`() = runTest {
        val api = FakeSyncApiClient(pullEvents = listOf(taskEvent(10)))
        api.failWith = java.io.IOException("connection reset")
        val engine = engine(api, this)

        val outcome = engine.syncOnce()
        val pull = (outcome as SyncOutcome.Completed).pull

        assertTrue(pull.isFailure, "a failed pull must not report success")
    }
}
