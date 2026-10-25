package com.singularity.todo.core.refresh

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.test.fakes.FakeCompletedTaskArchiver
import com.singularity.todo.test.fakes.FakeSyncRepository
import com.singularity.todo.test.helpers.awaitState
import com.singularity.todo.test.helpers.testVm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for [RefreshViewModel].
 *
 * The ViewModel owns two things and nothing else: the flag that says a refresh
 * is running, and the report. The run itself belongs to the
 * [RefreshCoordinator], and the flag has one hard requirement — it must come
 * down whatever happened, or the screen is stuck showing an indicator that no
 * longer means anything.
 */
@Tag("fast")
class RefreshViewModelTest {

    /**
     * The scope the coordinator's run lives in.
     *
     * A helper rather than an inline `val`: the coordinator's scope is not the
     * ViewModel's scope, and the ViewModel test file must not hand-roll the
     * ViewModel's scope.
     */
    private fun TestScope.coordinatorScope(): AutoCloseableCoroutineScope =
        AutoCloseableCoroutineScope(StandardTestDispatcher(testScheduler))

    @Test
    fun `the state goes Idle to Refreshing to Idle and the outcome arrives as an event`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val archiver = FakeCompletedTaskArchiver(outcome = Result.success(3)).also { it.gate = gate }
            val sync = FakeSyncRepository()
            val events = mutableListOf<RefreshUiEvent>()

            val ctx = testVm(
                stateAccessor = { vm: RefreshViewModel -> vm.stateFlow },
                afterInit = { vm, bgScope -> bgScope.launch { vm.events.collect { events += it } } },
            ) { scope ->
                RefreshViewModel(
                    coordinator = RefreshCoordinator(archiver, sync, coordinatorScope()),
                    scope = scope,
                )
            }

            assertIs<RefreshUiState.Idle>(ctx.state.value)

            ctx.act { onIntent(RefreshIntent.Refresh) }

            assertIs<RefreshUiState.Refreshing>(ctx.state.value)

            gate.complete(Unit)
            awaitState { ctx.state.value is RefreshUiState.Idle }

            // The event is delivered on the background scope, and
            // `advanceUntilIdle` — which is what `awaitState` spins on — stops
            // as soon as no *foreground* work is left. The collector is
            // background work, so one more dispatch is what puts the event in
            // the list. The harness's own `act` does the same thing for the
            // same reason.
            runCurrent()

            val finished = events.single() as RefreshUiEvent.Finished
            assertEquals(3, finished.outcome.archived.getOrThrow())
            assertEquals(sync.outcome, finished.outcome.sync)
        }

    @Test
    fun `the flag comes down even when the refresh failed`() =
        runTest {
            val archiver = FakeCompletedTaskArchiver(
                outcome = Result.failure(IllegalStateException("the archive step failed")),
            )
            val sync = FakeSyncRepository()
            val events = mutableListOf<RefreshUiEvent>()

            val ctx = testVm(
                stateAccessor = { vm: RefreshViewModel -> vm.stateFlow },
                afterInit = { vm, bgScope -> bgScope.launch { vm.events.collect { events += it } } },
            ) { scope ->
                RefreshViewModel(
                    coordinator = RefreshCoordinator(archiver, sync, coordinatorScope()),
                    scope = scope,
                )
            }

            ctx.act { onIntent(RefreshIntent.Refresh) }
            awaitState { ctx.state.value is RefreshUiState.Idle }

            assertIs<RefreshUiState.Idle>(ctx.state.value)
            val finished = events.single() as RefreshUiEvent.Finished
            assertEquals(true, finished.outcome.archived.isFailure)
        }

    @Test
    fun `the outcome is buffered while nobody listens and arrives when the screen comes back`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val archiver = FakeCompletedTaskArchiver(outcome = Result.success(1)).also { it.gate = gate }
            val sync = FakeSyncRepository()

            val ctx = testVm(
                stateAccessor = { vm: RefreshViewModel -> vm.stateFlow },
            ) { scope ->
                RefreshViewModel(
                    coordinator = RefreshCoordinator(archiver, sync, coordinatorScope()),
                    scope = scope,
                )
            }

            // Nobody is listening: the screen "left" before the refresh finished.
            ctx.act { onIntent(RefreshIntent.Refresh) }
            gate.complete(Unit)
            awaitState { ctx.state.value is RefreshUiState.Idle }

            // The screen came back and started listening.
            val events = mutableListOf<RefreshUiEvent>()
            ctx.backgroundScope.launch { ctx.vm.events.collect { events += it } }
            runCurrent()

            assertEquals(1, events.size, "the buffered outcome must reach the screen that came back")
        }

    @Test
    fun `a second intent while a refresh is running is ignored`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val archiver = FakeCompletedTaskArchiver(outcome = Result.success(1)).also { it.gate = gate }
            val sync = FakeSyncRepository()

            val ctx = testVm(
                stateAccessor = { vm: RefreshViewModel -> vm.stateFlow },
            ) { scope ->
                RefreshViewModel(
                    coordinator = RefreshCoordinator(archiver, sync, coordinatorScope()),
                    scope = scope,
                )
            }

            ctx.act { onIntent(RefreshIntent.Refresh) }
            ctx.act { onIntent(RefreshIntent.Refresh) }

            gate.complete(Unit)
            awaitState { ctx.state.value is RefreshUiState.Idle }

            assertEquals(1, archiver.archiveCalls, "the second intent must not start a second run")
            assertEquals(1, sync.syncOnceCalls, "the second intent must not start a second run")
        }
}
