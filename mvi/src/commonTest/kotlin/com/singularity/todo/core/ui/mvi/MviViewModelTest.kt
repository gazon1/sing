@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.ui.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviLogLevel
import com.singularity.todo.core.ui.MviViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

// --- Test state/intent/event types ---

/** Store key for the clear-through-lifecycle helper below. */
private const val LIFECYCLE_HARNESS_KEY = "mvi_lifecycle_harness"

sealed class TestState {
    data object Idle : TestState()

    data class Working(val progress: Int) : TestState()

    data class Error(val message: String) : TestState()
}

sealed interface TestIntent : MviIntent {
    data object Start : TestIntent

    data class UpdateProgress(val value: Int) : TestIntent

    data object Reset : TestIntent

    data object FailingToState : TestIntent

    data object FailingToEvent : TestIntent

    data object ThrowingToState : TestIntent

    data object Succeeding : TestIntent
}

sealed interface VmEvent : MviEvent {
    data class Notify(val msg: String) : VmEvent
}

/**
 * A ViewModel-owned scope whose `Job` is an explicit child of the test's, which is what a
 * consumer of this library is supposed to do.
 *
 * The parent is the test's `backgroundScope` rather than its root `Job`: `runTest` cancels
 * `backgroundScope` when the body returns, so a ViewModel that was never cleared still ends
 * instead of hanging the test for a minute. Parenting it to the root would make every test
 * responsible for cancelling the scope itself, and one that forgot would fail on a timeout
 * that says nothing about the ViewModel. An independent `SupervisorJob()` would be worse
 * still: not a child at all, so cancelling it is invisible and the test's completion never
 * reaches it.
 */
private fun TestScope.vmScope(): CoroutineScope {
    val parent = checkNotNull(backgroundScope.coroutineContext[Job]) { "runTest always provides a Job" }
    return CoroutineScope(coroutineContext + SupervisorJob(parent))
}

class VmUnderTest(testScope: CoroutineScope) :
    MviViewModel<TestState, TestIntent, VmEvent>(
        initialState = TestState.Idle,
        scope = testScope,
    ) {
    override fun onIntent(intent: TestIntent) {
        when (intent) {
            TestIntent.Start -> {
                vmScope.launch {
                    updateState { TestState.Working(0) }
                    emit(VmEvent.Notify("started"))
                }
            }

            is TestIntent.UpdateProgress -> {
                vmScope.launch {
                    updateState { current ->
                        if (current is TestState.Working) TestState.Working(intent.value) else current
                    }
                }
            }

            TestIntent.Reset -> {
                vmScope.launch { setState(TestState.Idle) }
            }

            TestIntent.FailingToState -> {
                vmScope.launch {
                    catchTo("state route failed", { msg -> updateState { TestState.Error(msg) } }) {
                        Result.failure<Unit>(IllegalStateException("inner"))
                    }
                }
            }

            TestIntent.FailingToEvent -> {
                vmScope.launch {
                    emitError("event route failed", VmEvent::Notify) {
                        Result.failure<Unit>(IllegalStateException("inner"))
                    }
                }
            }

            // Throws instead of returning a failure — the shape a `require(...)` or
            // `getOrThrow()` inside the block produces.
            TestIntent.ThrowingToState -> {
                vmScope.launch {
                    catchTo("state route failed", { msg -> updateState { TestState.Error(msg) } }) {
                        throw IllegalStateException("thrown inner")
                    }
                }
            }

            TestIntent.Succeeding -> {
                vmScope.launch {
                    catchTo("should not fire", { msg -> updateState { TestState.Error(msg) } }) {
                        Result.success(Unit)
                    }
                }
            }
        }
    }
}

/**
 * A ViewModel that runs its background work through the real error path: intent handlers
 * launch on the injected [MviViewModel.vmScope] via [MviViewModel.catchTo], exactly like the
 * production MVI loop. This is the shape whose teardown the injected scope lifecycle has to
 * protect (#3228).
 */
private class LifecycleHarness(
    initialState: TestState,
    scope: CoroutineScope,
    diagnostics: com.singularity.todo.core.ui.MviDiagnostics,
) : MviViewModel<TestState, TestIntent, VmEvent>(
        initialState = initialState,
        diagnostics = diagnostics,
        scope = scope,
    ) {
    fun startOperation(block: suspend () -> Result<*>): Job =
        catchTo("op_failed", onError = { msg -> updateState { TestState.Error(msg) } }) { block() }

    fun emitEventForTest(event: VmEvent): Boolean = tryEmit(event)

    override fun onIntent(intent: TestIntent) {
        // Intentionally not handled: this harness drives its work through `startOperation`.
    }
}

/**
 * Clears a ViewModel through the public lifecycle API.
 *
 * `ViewModel.clear()` is `internal` in lifecycle 2.11, so the public teardown path an
 * owner actually drives is the store: `ViewModelStore.clear()` resolves the stored
 * ViewModels and runs their clear — cancelling the `addCloseable` job — before dropping
 * them. Routing the clear through the store is what makes these tests exercise the real
 * contract rather than poking at a protected member directly.
 */
private fun clearThroughLifecycle(vm: ViewModel) =
    ViewModelStore().apply {
        put(LIFECYCLE_HARNESS_KEY, vm)
        clear()
    }

@Tag("fast")
class MviViewModelTest {
    @Test
    fun `initial state is correct`() =
        runTest {
            val vm = VmUnderTest(vmScope())
            assertTrue(vm.stateFlow.value is TestState.Idle)
        }

    @Test
    fun `onIntent dispatches to correct branch`() =
        runTest {
            val vm = VmUnderTest(vmScope())

            vm.onIntent(TestIntent.Start)
            advanceUntilIdle()

            assertTrue(vm.stateFlow.value is TestState.Working)
        }

    @Test
    fun `updateState keeps state when the transform guard does not match`() =
        runTest {
            val vm = VmUnderTest(vmScope())

            // Try to update Working state when in Idle — should stay Idle
            vm.onIntent(TestIntent.UpdateProgress(50))
            advanceUntilIdle()
            assertTrue(vm.stateFlow.value is TestState.Idle, "state should stay Idle since we weren't in Working")

            // Now enter Working
            vm.onIntent(TestIntent.Start)
            advanceUntilIdle()
            assertTrue(vm.stateFlow.value is TestState.Working)

            // Now update progress
            vm.onIntent(TestIntent.UpdateProgress(42))
            advanceUntilIdle()
            assertEquals(42, (vm.stateFlow.value as TestState.Working).progress)
        }

    @Test
    fun `emit fires one-shot event`() =
        runTest {
            val vm = VmUnderTest(vmScope())
            val events = mutableListOf<VmEvent>()

            val job = launch { vm.events.collect { events.add(it) } }
            advanceUntilIdle()
            vm.onIntent(TestIntent.Start)
            advanceUntilIdle()
            job.cancel()

            assertTrue(events.any { it is VmEvent.Notify }, "should have Notify event")
        }

    @Test
    fun `setState replaces the whole state`() =
        runTest {
            val vm = VmUnderTest(vmScope())

            vm.onIntent(TestIntent.Start)
            advanceUntilIdle()
            assertTrue(vm.stateFlow.value is TestState.Working)

            vm.onIntent(TestIntent.Reset)
            advanceUntilIdle()
            assertTrue(vm.stateFlow.value is TestState.Idle)
        }

    @Test
    fun `catchTo routes the throwable message into onError`() =
        runTest {
            val vm = VmUnderTest(vmScope())

            vm.onIntent(TestIntent.FailingToState)
            advanceUntilIdle()

            val state = vm.stateFlow.value
            assertTrue(state is TestState.Error, "expected Error, got $state")
            // errorLabel is a FALLBACK, not a prefix: the throwable's own message wins.
            assertEquals("inner", state.message)
        }

    @Test
    fun `catchTo converts a thrown exception into an error state instead of crashing the coroutine`() =
        runTest {
            val vm = VmUnderTest(vmScope())

            vm.onIntent(TestIntent.ThrowingToState)
            advanceUntilIdle()

            val state = vm.stateFlow.value
            assertTrue(state is TestState.Error, "expected Error, got $state")
            assertEquals("thrown inner", state.message)
        }

    @Test
    fun `catchTo leaves state untouched when the block succeeds`() =
        runTest {
            val vm = VmUnderTest(vmScope())

            vm.onIntent(TestIntent.Succeeding)
            advanceUntilIdle()

            assertTrue(vm.stateFlow.value is TestState.Idle)
        }

    @Test
    fun `emitError emits an event when the block fails`() =
        runTest {
            val vm = VmUnderTest(vmScope())
            val events = mutableListOf<VmEvent>()
            val job = launch { vm.events.collect { events.add(it) } }
            advanceUntilIdle()

            vm.onIntent(TestIntent.FailingToEvent)
            advanceUntilIdle()
            job.cancel()

            assertTrue(
                events.any { it is VmEvent.Notify && it.msg == "inner" },
                "expected the throwable message in the event, got $events",
            )
        }

    @Test
    fun `state is a read-only view of stateFlow`() =
        runTest {
            val vm = VmUnderTest(vmScope())

            // The public handle must not be a MutableStateFlow. A caller that could write
            // it would put the screen in a state the ViewModel never decided — which is
            // exactly the drift the event bus cannot see.
            assertFalse(vm.state is MutableStateFlow<*>, "state must not expose a mutable flow")

            // The same flow, not two flows that happen to carry equal values.
            assertSame(vm.stateFlow, vm.state, "state must be the stateFlow itself")

            vm.onIntent(TestIntent.Start)
            advanceUntilIdle()

            assertEquals(vm.stateFlow.value, vm.state.value, "state and stateFlow are the same flow")
        }

    // ── Diagnostics seam ─────────────────────────────────────────────────────

    @Test
    fun `a handled failure is classified once and reported with the classifier's key`() =
        runTest {
            val diagnostics = RecordingMviDiagnostics()
            val vm = LifecycleHarness(TestState.Idle, scope = vmScope(), diagnostics = diagnostics)

            vm.startOperation { Result.failure<Unit>(IllegalStateException("inner")) }
            advanceUntilIdle()

            assertEquals(1, diagnostics.reports.size, "one report per handled failure")
            val (issueKey, reported) = diagnostics.reports.single()
            assertEquals("op_failed", issueKey, "the default classifier groups by the call-site label")
            assertEquals("inner", reported.message)
        }

    @Test
    fun `a successful block reports nothing`() =
        runTest {
            val diagnostics = RecordingMviDiagnostics()
            val vm = LifecycleHarness(TestState.Idle, scope = vmScope(), diagnostics = diagnostics)

            vm.startOperation { Result.success(Unit) }
            advanceUntilIdle()

            assertTrue(diagnostics.reports.isEmpty(), "success must not reach the reporting layer")
        }

    @Test
    fun `a cancelled operation is not a failure and is not reported`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val diagnostics = RecordingMviDiagnostics()
            val vm = LifecycleHarness(TestState.Idle, scope = vmScope(), diagnostics = diagnostics)
            val job =
                vm.startOperation {
                    gate.await()
                    Result.success(Unit)
                }

            runCurrent()
            assertFalse(job.isCancelled, "the operation is in flight before the clear")

            clearThroughLifecycle(vm)
            runCurrent()

            assertTrue(job.isCancelled, "clearing the ViewModel must cancel the work its scope launched")
            assertTrue(diagnostics.reports.isEmpty(), "a cancelled coroutine is not a failure and must not be reported")
        }

    // ── Scope ownership ──────────────────────────────────────────────────────

    @Test
    fun `a scope without a Job is rejected at construction`() =
        runTest {
            // A Job-less context would make teardown a silent no-op and leak every coroutine
            // the ViewModel launched. Fail loudly at construction instead.
            //
            // Built by hand rather than through `CoroutineScope(context)`: that factory *adds*
            // a `Job` when the context has none, so it cannot express the case under test.
            val jobless =
                object : CoroutineScope {
                    override val coroutineContext: CoroutineContext = Dispatchers.Default
                }

            val thrown =
                assertFailsWith<IllegalArgumentException> {
                    LifecycleHarness(TestState.Idle, scope = jobless, diagnostics = RecordingMviDiagnostics())
                }
            assertTrue(
                "Job" in (thrown.message ?: ""),
                "the message must say what is missing: ${thrown.message}",
            )
        }

    @Test
    fun `clearing the viewmodel cancels a child launched on its scope`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val vm = LifecycleHarness(TestState.Idle, scope = vmScope(), diagnostics = RecordingMviDiagnostics())
            val job =
                vm.startOperation {
                    gate.await()
                    Result.success(Unit)
                }

            runCurrent()
            assertFalse(job.isCancelled, "the operation is in flight before the clear")

            clearThroughLifecycle(vm)
            runCurrent()

            assertTrue(job.isCancelled, "clearing the ViewModel must cancel the work its scope launched")
        }

    @Test
    fun `clearing the viewmodel does not cancel the parent scope`() =
        runTest {
            val parent = checkNotNull(coroutineContext[Job])
            val vm = LifecycleHarness(TestState.Idle, scope = vmScope(), diagnostics = RecordingMviDiagnostics())
            clearThroughLifecycle(vm)

            var foregroundWorkRan = false
            launch { foregroundWorkRan = true }
            advanceUntilIdle()

            assertTrue(foregroundWorkRan, "clearing the ViewModel must not take down the surrounding test scope")
            assertFalse(parent.isCancelled, "the parent Job the child scope hangs off is untouched")
        }

    @Test
    fun `clearing the viewmodel closes the event bus`() =
        runTest {
            val vm = LifecycleHarness(TestState.Idle, scope = vmScope(), diagnostics = RecordingMviDiagnostics())

            assertTrue(vm.emitEventForTest(VmEvent.Notify("before close")))

            clearThroughLifecycle(vm)
            assertTrue(
                !vm.emitEventForTest(VmEvent.Notify("after close")),
                "the event bus must be closed on clear, so emits from dying coroutines are discarded",
            )
        }

    @Test
    fun `a refused event is logged once at warn through the diagnostics seam`() =
        runTest {
            val diagnostics = RecordingMviDiagnostics()
            val vm = LifecycleHarness(TestState.Idle, scope = vmScope(), diagnostics = diagnostics)

            // A rendezvous-free buffered bus: fill it, then refuse repeatedly.
            while (vm.emitEventForTest(VmEvent.Notify("fill"))) {
                // keep filling
            }
            vm.emitEventForTest(VmEvent.Notify("refused"))

            val warns = diagnostics.lines.filter { it.first == MviLogLevel.Warn }
            assertEquals(1, warns.size, "one line per bus, not one per refusal: ${diagnostics.lines}")
            vm.stateFlow.value // the ViewModel is still usable after a refusal
        }
}
