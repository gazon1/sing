package com.singularity.todo.core.refresh

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.sync.SyncOutcome
import com.singularity.todo.test.fakes.FakeCompletedTaskArchiver
import com.singularity.todo.test.fakes.FakeSyncRepository
import com.singularity.todo.test.fakes.RefreshCallLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [RefreshCoordinator] — the single owner of a refresh.
 *
 * The properties under test, and why each one is a property rather than a
 * detail:
 *
 * - **One run, two steps.** The archive step runs before the synchronisation
 *   step. The order is the reason the archive reaches the server in the same
 *   gesture: archiving writes to the outgoing queue, and a push that went
 *   first would leave the server holding the un-archived copy.
 * - **The steps fail independently.** A failed archive still synchronises; a
 *   failed synchronisation still archives.
 * - **A second request joins the running one.** One archive pass, one
 *   synchronisation, one outcome for both requesters.
 * - **The run outlives the caller.** A user who walks away mid-refresh still
 *   gets the archive step and the synchronisation.
 */
@Tag("fast")
class RefreshCoordinatorTest {

    private fun TestScope.scope(): AutoCloseableCoroutineScope =
        AutoCloseableCoroutineScope(StandardTestDispatcher(testScheduler))

    // ─── One request, one run ─────────────────────────────────────────────

    @Test
    fun `one request runs one synchronisation and returns its outcome`() =
        runTest {
            val sync = FakeSyncRepository()
            val archiver = FakeCompletedTaskArchiver(outcome = Result.success(0))
            val coordinator = RefreshCoordinator(archiver, sync, scope())

            val outcome = coordinator.request()

            assertEquals(1, sync.syncOnceCalls)
            assertEquals(1, archiver.archiveCalls)
            assertEquals(sync.outcome, outcome.sync)
        }

    @Test
    fun `the outcome carries the count the archiver returned`() =
        runTest {
            val archiver = FakeCompletedTaskArchiver(outcome = Result.success(7))
            val coordinator = RefreshCoordinator(archiver, FakeSyncRepository(), scope())

            val outcome = coordinator.request()

            assertEquals(7, outcome.archived.getOrThrow())
        }

    // ─── The order of the two steps ──────────────────────────────────────

    @Test
    fun `the archive step runs before the synchronisation step`() =
        runTest {
            val log = RefreshCallLog()
            val archiver = FakeCompletedTaskArchiver(outcome = Result.success(1), log = log)
            val sync = FakeSyncRepository(log = log)
            val coordinator = RefreshCoordinator(archiver, sync, scope())

            coordinator.request()

            assertEquals(
                listOf(RefreshCallLog.ARCHIVE_STEP, RefreshCallLog.SYNC_STEP),
                log.snapshot(),
            )
        }

    // ─── The steps fail independently ─────────────────────────────────────

    @Test
    fun `a failed archive step does not stop the synchronisation`() =
        runTest {
            val archiver = FakeCompletedTaskArchiver(
                outcome = Result.failure(IllegalStateException("the archive step failed")),
            )
            val sync = FakeSyncRepository()
            val coordinator = RefreshCoordinator(archiver, sync, scope())

            val outcome = coordinator.request()

            assertTrue(outcome.archived.isFailure)
            assertEquals(1, sync.syncOnceCalls)
        }

    @Test
    fun `a failed synchronisation leaves the archive result intact`() =
        runTest {
            val archiver = FakeCompletedTaskArchiver(outcome = Result.success(2))
            val sync = FakeSyncRepository()
            sync.outcome = SyncOutcome.CouldNotStart(AppError.Network("no route to host"))
            val coordinator = RefreshCoordinator(archiver, sync, scope())

            val outcome = coordinator.request()

            assertEquals(2, outcome.archived.getOrThrow())
            assertIs<SyncOutcome.CouldNotStart>(outcome.sync)
        }

    @Test
    fun `a synchronisation that throws leaves the archive result intact`() =
        runTest {
            val archiver = FakeCompletedTaskArchiver(outcome = Result.success(2))
            val sync = FakeSyncRepository()
            sync.throwsOnCall = IllegalStateException("the repository threw")
            val coordinator = RefreshCoordinator(archiver, sync, scope())

            val outcome = coordinator.request()

            assertEquals(2, outcome.archived.getOrThrow())
            assertIs<SyncOutcome.CouldNotStart>(outcome.sync)
        }

    @Test
    fun `an archive step that throws does not stop the synchronisation`() =
        runTest {
            val archiver = FakeCompletedTaskArchiver(outcome = Result.success(0))
            archiver.throwsOnCall = IllegalStateException("the repository threw")
            val sync = FakeSyncRepository()
            val coordinator = RefreshCoordinator(archiver, sync, scope())

            val outcome = coordinator.request()

            assertTrue(outcome.archived.isFailure)
            assertEquals(1, sync.syncOnceCalls)
        }

    // ─── A second request joins the running one ───────────────────────────

    @Test
    fun `a second request during a running refresh joins it`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val archiver = FakeCompletedTaskArchiver(outcome = Result.success(1)).also { it.gate = gate }
            val sync = FakeSyncRepository()
            val coordinator = RefreshCoordinator(archiver, sync, scope())

            val first = async { coordinator.request() }
            runCurrent()

            val second = async { coordinator.request() }
            runCurrent()

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, archiver.archiveCalls, "the second request must not archive again")
            assertEquals(1, sync.syncOnceCalls, "the second request must not synchronise again")
            assertEquals(first.await(), second.await(), "both requesters get the same outcome")
        }

    @Test
    fun `a request after the refresh finished starts a new one`() =
        runTest {
            val archiver = FakeCompletedTaskArchiver(outcome = Result.success(1))
            val sync = FakeSyncRepository()
            val coordinator = RefreshCoordinator(archiver, sync, scope())

            coordinator.request()
            coordinator.request()

            assertEquals(2, archiver.archiveCalls)
            assertEquals(2, sync.syncOnceCalls)
        }

    // ─── The run outlives the caller ──────────────────────────────────────

    @Test
    fun `the refresh finishes even when the caller goes away`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val archiver = FakeCompletedTaskArchiver(outcome = Result.success(1)).also { it.gate = gate }
            val sync = FakeSyncRepository()
            val coordinator = RefreshCoordinator(archiver, sync, scope())

            val caller = async { coordinator.request() }
            runCurrent()

            caller.cancel()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, archiver.archiveCalls)
            assertEquals(1, sync.syncOnceCalls)
        }

    // ─── A closed coordinator ─────────────────────────────────────────────

    @Test
    fun `a request after close reports that nothing happened`() =
        runTest {
            val archiver = FakeCompletedTaskArchiver(outcome = Result.success(1))
            val sync = FakeSyncRepository()
            val coordinator = RefreshCoordinator(archiver, sync, scope())

            coordinator.close()

            val outcome = coordinator.request()

            assertEquals(0, outcome.archived.getOrThrow())
            assertIs<SyncOutcome.NothingToDo>(outcome.sync)
            assertEquals(0, archiver.archiveCalls)
            assertEquals(0, sync.syncOnceCalls)
        }
}
