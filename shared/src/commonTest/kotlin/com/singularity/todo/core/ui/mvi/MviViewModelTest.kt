package com.singularity.todo.core.ui.mvi

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
}

sealed interface VmEvent : MviEvent {
    data class Notify(val msg: String) : VmEvent
}

class VmUnderTest(
    private val testScope: CoroutineScope,
) : MviViewModel<TestState, TestIntent, VmEvent>(
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
        delay(50)

        assertTrue(vm.state.value is TestState.Working)
    }

    @Test
    fun `updateState only updates matching state type`() = runTest {
        val vm = VmUnderTest(this)

        // Try to update Working state when in Idle — should stay Idle
        vm.onIntent(TestIntent.UpdateProgress(50))
        delay(50)
        assertTrue(vm.state.value is TestState.Idle, "state should stay Idle since we weren't in Working")

        // Now enter Working
        vm.onIntent(TestIntent.Start)
        delay(50)
        assertTrue(vm.state.value is TestState.Working)

        // Now update progress
        vm.onIntent(TestIntent.UpdateProgress(42))
        delay(50)
        assertEquals(42, (vm.state.value as TestState.Working).progress)
    }

    @Test
    fun `emit fires one-shot event`() = runTest {
        val vm = VmUnderTest(this)
        val events = mutableListOf<VmEvent>()

        val job = launch { vm.events.collect { events.add(it) } }
        delay(50)
        vm.onIntent(TestIntent.Start)
        delay(50)
        job.cancel()

        assertTrue(events.any { it is VmEvent.Notify }, "should have Notify event")
    }
}
