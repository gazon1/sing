@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.sync.work.FakeSyncWorkScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
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
        scheduler = FakeSyncWorkScheduler(),
        // `backgroundScope`, not the test scope. The engine's init collects the auth
        // session forever, so a scope parented to the test's own job would leave an
        // active child at the end of the test body — which `runTest` reports as
        // UncompletedCoroutinesError after a full minute of waiting.
        scope = testScope(scope.backgroundScope),
        crashReporter = NoOpCrashReportingPort(),
    )

    private fun signedIn() = Session.SignedIn(UserId.generate(), "t@x.com", "access", "refresh")

    private fun taskEvent(lsn: Long, profile: String = "") = syncEvent {
        serverLsn = lsn
        entityType = DocType.Task
        entityId = "task-$lsn"
        profileId = profile
    }

    private fun event(lsn: Long, type: DocType) = syncEvent {
        serverLsn = lsn
        entityType = type
        entityId = "e-$lsn"
    }

    private fun recordApplied(into: MutableList<Long>) = EntityApply { event ->
        into += event.serverLsn
        ApplyOutcome.Applied
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
        val pull = (outcome as SyncOutcome.Success).pull.getOrThrow()

        assertEquals(listOf(10L), applied, "only the task event should be applied")
        // 10, not 0: the cursor is the last position that was *applied*. lsn 20 is not
        // consumed, so the next pull asks from 10 and receives it again. The original
        // bug stored 20 here, and the event was never seen again.
        assertEquals(10L, state.lastLsn(syncScope), "the cursor must stop before the unknown type")
        assertEquals(1, pull.dropped, "the unappliable event must be reported")
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
        val pull = (outcome as SyncOutcome.Success).pull.getOrThrow()

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

    @Test
    fun `a conflict is counted but still advances the cursor`() = runTest {
        val api = FakeSyncApiClient(pullEvents = listOf(taskEvent(10), taskEvent(20)))
        val auth = FakeSyncAuthRepository(signedIn())
        val state = FakeSyncStateRepository()
        val syncScope = scopeFor(auth)
        val engine = engine(api, this, stateRepository = state, auth = auth)
        engine.registerHandler(DocType.Task) { ApplyOutcome.Conflict("newer remote value") }

        val outcome = engine.syncOnce()
        val pull = (outcome as SyncOutcome.Success).pull.getOrThrow()

        assertEquals(2, pull.conflicts)
        assertEquals(0, pull.dropped)
        assertEquals(20L, state.lastLsn(syncScope), "a resolved conflict is not a reason to re-fetch")
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
        assertTrue(outcome is SyncOutcome.Skipped, "without a scope there is nothing to cycle")
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
        val pull = (outcome as SyncOutcome.Success).pull.getOrThrow()

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
        val pull = (outcome as SyncOutcome.Success).pull.getOrThrow()

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
        val pull = (outcome as SyncOutcome.Success).pull

        assertTrue(pull.isFailure, "a failed pull must not report success")
    }
}
