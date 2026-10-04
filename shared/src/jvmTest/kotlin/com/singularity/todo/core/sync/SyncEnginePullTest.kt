@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
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

    private fun engine(
        api: FakeSyncApiClient,
        scope: TestScope,
        prefs: SyncPrefs = FakeSyncPrefs(),
        auth: FakeSyncAuthRepository = FakeSyncAuthRepository(signedIn()),
    ) = SyncEngine(
        log = log,
        api = api,
        authRepository = auth,
        outboxDao = FakeSyncOutboxDao(),
        deadLetterDao = FakeSyncDeadLetterDao(),
        idGenerator = SequentialIdGenerator(),
        prefs = prefs,
        scheduler = FakeSyncWorkScheduler(),
        // `backgroundScope`, not the test scope. The engine's init collects the auth
        // session forever, so a scope parented to the test's own job would leave an
        // active child at the end of the test body — which `runTest` reports as
        // UncompletedCoroutinesError after a full minute of waiting.
        scope = testScope(scope.backgroundScope),
    )

    private fun signedIn() = Session.SignedIn(UserId.generate(), "t@x.com", "access", "refresh")

    private fun taskEvent(lsn: Long) = syncEvent {
        serverLsn = lsn
        entityType = DocType.Task
        entityId = "task-$lsn"
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
        val prefs = FakeSyncPrefs()
        val applied = mutableListOf<Long>()
        val engine = engine(api, this, prefs)
        // Only DocType.Task is registered. Note and Tag are unknown to this client.
        engine.registerHandler(DocType.Task, recordApplied(applied))

        val outcome = engine.syncOnce()
        val pull = (outcome as SyncOutcome.Success).pull.getOrThrow()

        assertEquals(listOf(10L), applied, "only the task event should be applied")
        // 10, not 0: the cursor is the last position that was *applied*. lsn 20 is not
        // consumed, so the next pull asks from 10 and receives it again. The original
        // bug stored 20 here, and the event was never seen again.
        assertEquals(10L, prefs.lastLsn, "the cursor must stop before the unknown type")
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
        val prefs = FakeSyncPrefs()
        val applied = mutableListOf<Long>()
        val engine = engine(api, this, prefs)
        engine.registerHandler(DocType.Task, recordApplied(applied))

        engine.syncOnce()

        // lsn 30 is a type this client CAN apply, but it arrives after one it cannot.
        // Stopping the loop leaves it for the next cycle rather than consuming it.
        assertEquals(listOf(10L), applied)
        assertEquals(10L, prefs.lastLsn, "lsn 30 must not be consumed by this cycle")
    }

    @Test
    fun `the cursor advances past events that were applied`() = runTest {
        val api = FakeSyncApiClient(pullEvents = listOf(taskEvent(10), taskEvent(20)))
        val prefs = FakeSyncPrefs()
        val applied = mutableListOf<Long>()
        val engine = engine(api, this, prefs)
        engine.registerHandler(DocType.Task, recordApplied(applied))

        val outcome = engine.syncOnce()
        val pull = (outcome as SyncOutcome.Success).pull.getOrThrow()

        assertEquals(listOf(10L, 20L), applied)
        assertEquals(20L, prefs.lastLsn)
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
        val prefs = FakeSyncPrefs()
        val engine = engine(api, this, prefs)
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
        val prefs = FakeSyncPrefs()
        val engine = engine(api, this, prefs)
        engine.registerHandler(DocType.Task) { ApplyOutcome.Conflict("newer remote value") }

        val outcome = engine.syncOnce()
        val pull = (outcome as SyncOutcome.Success).pull.getOrThrow()

        assertEquals(2, pull.conflicts)
        assertEquals(0, pull.dropped)
        assertEquals(20L, prefs.lastLsn, "a resolved conflict is not a reason to re-fetch")
    }

    @Test
    fun `a signed-out client pulls nothing`() = runTest {
        val api = FakeSyncApiClient(pullEvents = listOf(taskEvent(10)))
        val engine = engine(api, this, auth = FakeSyncAuthRepository(Session.SignedOut))

        val outcome = engine.syncOnce()
        val pull = (outcome as SyncOutcome.Success).pull.getOrThrow()

        assertEquals(0, pull.received)
        assertTrue(api.pullCalls.isEmpty(), "a signed-out client must not hit the server")
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
