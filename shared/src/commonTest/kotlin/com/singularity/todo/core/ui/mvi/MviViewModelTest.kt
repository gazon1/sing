package com.singularity.todo.core.ui.mvi

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// --- Test state/intent/event types ---

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
            TestIntent.Start -> testScope.launch {
                updateState { TestState.Working(0) }
                emit(VmEvent.Notify("started"))
            }

            is TestIntent.UpdateProgress -> testScope.launch {
                updateState { current ->
                    if (current is TestState.Working) TestState.Working(intent.value) else current
                }
            }

            TestIntent.Fail -> testScope.launch {
                updateState { TestState.Error("boom") }
                emit(VmEvent.Notify("failed"))
            }

            TestIntent.Reset -> testScope.launch { setState(TestState.Idle) }

            TestIntent.FailingToState -> testScope.launch {
                catchTo("state route failed", { msg -> updateState { TestState.Error(msg) } }) {
                    Result.failure<Unit>(IllegalStateException("inner"))
                }
            }

            TestIntent.FailingToEvent -> testScope.launch {
                emitError("event route failed", VmEvent::Notify) {
                    Result.failure<Unit>(IllegalStateException("inner"))
                }
            }

            TestIntent.FailingWithAppError -> testScope.launch {
                catchTo("unused label", { msg -> updateState { TestState.Error(msg) } }) {
                    Result.failure<Unit>(AppError.Validation("domain says no"))
                }
            }

            // Throws instead of returning a failure — the shape a `require(...)` or
            // `getOrThrow()` inside the block produces.
            TestIntent.ThrowingToState -> testScope.launch {
                catchTo("state route failed", { msg -> updateState { TestState.Error(msg) } }) {
                    throw IllegalStateException("thrown inner")
                }
            }

            TestIntent.ThrowingToEvent -> testScope.launch {
                emitError("event route failed", VmEvent::Notify) {
                    throw AppError.NotFound("thrown domain says missing")
                }
            }

            TestIntent.Succeeding -> testScope.launch {
                catchTo("should not fire", { msg -> updateState { TestState.Error(msg) } }) {
                    Result.success(Unit)
                }
            }
        }
    }
}

class MviViewModelTest {
    @Test
    fun `initial state is correct`() = runTest {
        val vm = VmUnderTest(this)
        assertTrue(vm.state.value is TestState.Idle)
    }

    @Test
    fun `onIntent dispatches to correct branch`() = runTest {
        val vm = VmUnderTest(this)

        vm.onIntent(TestIntent.Start)
        advanceUntilIdle()

        assertTrue(vm.state.value is TestState.Working)
    }

    @Test
    fun `updateState keeps state when the transform guard does not match`() = runTest {
        val vm = VmUnderTest(this)

        // Try to update Working state when in Idle — should stay Idle
        vm.onIntent(TestIntent.UpdateProgress(50))
        advanceUntilIdle()
        assertTrue(vm.state.value is TestState.Idle, "state should stay Idle since we weren't in Working")

        // Now enter Working
        vm.onIntent(TestIntent.Start)
        advanceUntilIdle()
        assertTrue(vm.state.value is TestState.Working)

        // Now update progress
        vm.onIntent(TestIntent.UpdateProgress(42))
        advanceUntilIdle()
        assertEquals(42, (vm.state.value as TestState.Working).progress)
    }

    @Test
    fun `emit fires one-shot event`() = runTest {
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
    fun `setState replaces the whole state`() = runTest {
        val vm = VmUnderTest(this)

        vm.onIntent(TestIntent.Start)
        advanceUntilIdle()
        assertTrue(vm.state.value is TestState.Working)

        vm.onIntent(TestIntent.Reset)
        advanceUntilIdle()
        assertTrue(vm.state.value is TestState.Idle)
    }

    @Test
    fun `catchTo routes the throwable message into onError`() = runTest {
        val vm = VmUnderTest(this)

        vm.onIntent(TestIntent.FailingToState)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is TestState.Error, "expected Error, got $state")
        // errorLabel is a FALLBACK, not a prefix: the throwable's own message wins.
        assertEquals("inner", state.message)
    }

    @Test
    fun `catchTo prefers an AppError message over the throwable and the label`() = runTest {
        val vm = VmUnderTest(this)

        vm.onIntent(TestIntent.FailingWithAppError)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is TestState.Error, "expected Error, got $state")
        assertEquals("domain says no", state.message)
    }

    @Test
    fun `catchTo converts a thrown exception into an error state instead of crashing the coroutine`() = runTest {
        val vm = VmUnderTest(this)

        vm.onIntent(TestIntent.ThrowingToState)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is TestState.Error, "expected Error, got $state")
        assertEquals("thrown inner", state.message)
    }

    @Test
    fun `emitError converts a thrown AppError into an event instead of crashing the coroutine`() = runTest {
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
    fun `catchTo leaves state untouched when the block succeeds`() = runTest {
        val vm = VmUnderTest(this)

        vm.onIntent(TestIntent.Succeeding)
        advanceUntilIdle()

        assertTrue(vm.state.value is TestState.Idle)
    }

    @Test
    fun `emitError emits an event when the block fails`() = runTest {
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
}
