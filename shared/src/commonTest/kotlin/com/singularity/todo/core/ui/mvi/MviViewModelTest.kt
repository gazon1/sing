@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.ui.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.test.fakes.RecordingCrashReportingPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    data object Fail : TestIntent

    data object Reset : TestIntent

    data object FailingToState : TestIntent

    data object FailingToEvent : TestIntent

    data object FailingWithAppError : TestIntent

    data object ThrowingToState : TestIntent

    data object ThrowingToEvent : TestIntent

    data object Succeeding : TestIntent
}

sealed interface VmEvent : MviEvent {
    data class Notify(val msg: String) : VmEvent
}

class VmUnderTest(private val testScope: CoroutineScope) :
    MviViewModel<TestState, TestIntent, VmEvent>(
        initialState = TestState.Idle,
        scope = AutoCloseableCoroutineScope(testScope.coroutineContext),
    ) {
    override fun onIntent(intent: TestIntent) {
        when (intent) {
            TestIntent.Start -> {
                testScope.launch {
                    updateState { TestState.Working(0) }
                    emit(VmEvent.Notify("started"))
                }
            }

            is TestIntent.UpdateProgress -> {
                testScope.launch {
                    updateState { current ->
                        if (current is TestState.Working) TestState.Working(intent.value) else current
                    }
                }
            }

            TestIntent.Fail -> {
                testScope.launch {
                    updateState { TestState.Error("boom") }
                    emit(VmEvent.Notify("failed"))
                }
            }

            TestIntent.Reset -> {
                testScope.launch { setState(TestState.Idle) }
            }

            TestIntent.FailingToState -> {
                testScope.launch {
                    catchTo("state route failed", { msg -> updateState { TestState.Error(msg) } }) {
                        Result.failure<Unit>(IllegalStateException("inner"))
                    }
                }
            }

            TestIntent.FailingToEvent -> {
                testScope.launch {
                    emitError("event route failed", VmEvent::Notify) {
                        Result.failure<Unit>(IllegalStateException("inner"))
                    }
                }
            }

            TestIntent.FailingWithAppError -> {
                testScope.launch {
                    catchTo("unused label", { msg -> updateState { TestState.Error(msg) } }) {
                        Result.failure<Unit>(AppError.Validation("domain says no"))
                    }
                }
            }

            // Throws instead of returning a failure — the shape a `require(...)` or
            // `getOrThrow()` inside the block produces.
            TestIntent.ThrowingToState -> {
                testScope.launch {
                    catchTo("state route failed", { msg -> updateState { TestState.Error(msg) } }) {
                        throw IllegalStateException("thrown inner")
                    }
                }
            }

            TestIntent.ThrowingToEvent -> {
                testScope.launch {
                    emitError("event route failed", VmEvent::Notify) {
                        throw AppError.NotFound("thrown domain says missing")
                    }
                }
            }

            TestIntent.Succeeding -> {
                testScope.launch {
                    catchTo("should not fire", { msg -> updateState { TestState.Error(msg) } }) {
                        Result.success(Unit)
                    }
                }
            }
        }
    }
}

/**
 * A ViewModel that runs its background work through the real error path: intent
 * handlers launch on the injected [MviViewModel.vmScope] via [MviViewModel.catchTo],
 * exactly like the production MVI loop. This is the shape whose teardown the injected
 * scope lifecycle has to protect (#3228).
 */
private class LifecycleHarness(
    initialState: TestState,
    scope: AutoCloseableCoroutineScope,
    crashReporter: CrashReportingPort,
) : MviViewModel<TestState, TestIntent, VmEvent>(
        initialState = initialState,
        crashReporter = crashReporter,
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
 * ViewModels and runs their clear — cancelling the `addCloseable` scope — before dropping
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
            val vm = VmUnderTest(this)
            assertTrue(vm.stateFlow.value is TestState.Idle)
        }

    @Test
    fun `onIntent dispatches to correct branch`() =
        runTest {
            val vm = VmUnderTest(this)

            vm.onIntent(TestIntent.Start)
            advanceUntilIdle()

            assertTrue(vm.stateFlow.value is TestState.Working)
        }

    @Test
    fun `updateState keeps state when the transform guard does not match`() =
        runTest {
            val vm = VmUnderTest(this)

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
            val vm = VmUnderTest(this)
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
            val vm = VmUnderTest(this)

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
            val vm = VmUnderTest(this)

            vm.onIntent(TestIntent.FailingToState)
            advanceUntilIdle()

            val state = vm.stateFlow.value
            assertTrue(state is TestState.Error, "expected Error, got $state")
            // errorLabel is a FALLBACK, not a prefix: the throwable's own message wins.
            assertEquals("inner", state.message)
        }

    @Test
    fun `catchTo prefers an AppError message over the throwable and the label`() =
        runTest {
            val vm = VmUnderTest(this)

            vm.onIntent(TestIntent.FailingWithAppError)
            advanceUntilIdle()

            val state = vm.stateFlow.value
            assertTrue(state is TestState.Error, "expected Error, got $state")
            assertEquals("domain says no", state.message)
        }

    @Test
    fun `catchTo converts a thrown exception into an error state instead of crashing the coroutine`() =
        runTest {
            val vm = VmUnderTest(this)

            vm.onIntent(TestIntent.ThrowingToState)
            advanceUntilIdle()

            val state = vm.stateFlow.value
            assertTrue(state is TestState.Error, "expected Error, got $state")
            assertEquals("thrown inner", state.message)
        }

    @Test
    fun `emitError converts a thrown AppError into an event instead of crashing the coroutine`() =
        runTest {
            val vm = VmUnderTest(this)
            val events = mutableListOf<VmEvent>()
            val job = launch { vm.events.collect { events.add(it) } }
            advanceUntilIdle()

            vm.onIntent(TestIntent.ThrowingToEvent)
            advanceUntilIdle()
            job.cancel()

            assertTrue(
                events.any { it is VmEvent.Notify && it.msg == "thrown domain says missing" },
                "expected the thrown AppError message in the event, got $events",
            )
        }

    @Test
    fun `catchTo leaves state untouched when the block succeeds`() =
        runTest {
            val vm = VmUnderTest(this)

            vm.onIntent(TestIntent.Succeeding)
            advanceUntilIdle()

            assertTrue(vm.stateFlow.value is TestState.Idle)
        }

    @Test
    fun `emitError emits an event when the block fails`() =
        runTest {
            val vm = VmUnderTest(this)
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

    // ── Injected-scope lifecycle (#3228) ─────────────────────────────────────

    @Test
    fun `clearing the viewmodel cancels a child launched on its scope`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val reporter = RecordingCrashReportingPort()
            val vm = LifecycleHarness(TestState.Idle, scope = testScope(this), crashReporter = reporter)
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
            assertTrue(reporter.reports.isEmpty(), "a cancelled coroutine is not a failure and must not be reported")
        }

    @Test
    fun `clearing the viewmodel does not cancel the test scope`() =
        runTest {
            val vm =
                LifecycleHarness(
                    TestState.Idle,
                    scope = testScope(this),
                    crashReporter = RecordingCrashReportingPort(),
                )
            clearThroughLifecycle(vm)

            var foregroundWorkRan = false
            launch { foregroundWorkRan = true }
            advanceUntilIdle()

            assertTrue(foregroundWorkRan, "clearing the ViewModel must not take down the surrounding test scope")
        }

    @Test
    fun `clearing the viewmodel closes the event bus`() =
        runTest {
            val vm =
                LifecycleHarness(
                    TestState.Idle,
                    scope = testScope(this),
                    crashReporter = RecordingCrashReportingPort(),
                )

            assertTrue(vm.emitEventForTest(VmEvent.Notify("before close")))

            clearThroughLifecycle(vm)
            assertTrue(
                !vm.emitEventForTest(VmEvent.Notify("after close")),
                "the event bus must be closed on clear, so emits from dying coroutines are discarded",
            )
        }
}
