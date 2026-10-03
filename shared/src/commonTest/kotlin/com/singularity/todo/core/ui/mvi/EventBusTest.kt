@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.ui.mvi

import com.singularity.todo.core.ui.EventBus
import com.singularity.todo.core.ui.MviEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

interface TestEvent : MviEvent {
    data class Text(val message: String) : TestEvent
    data object Signal : TestEvent
}

class EventBusTest {
    @Test
    fun `EventBus emits events to collector`() = runTest {
        val bus = EventBus<TestEvent>()
        val results = mutableListOf<TestEvent>()

        launch {
            bus.flow.collect { results.add(it) }
        }.let { job ->
            bus.emit(TestEvent.Text("hello"))
            advanceUntilIdle()
            bus.emit(TestEvent.Signal)
            advanceUntilIdle()
            job.cancel()
        }

        assertEquals(2, results.size)
        assertTrue(results[0] is TestEvent.Text)
        assertEquals("hello", (results[0] as TestEvent.Text).message)
        assertEquals(TestEvent.Signal, results[1])
    }

    @Test
    fun `EventBus tryEmit returns true when buffer has capacity`() = runTest {
        val bus = EventBus<TestEvent>(capacity = 1)
        assertTrue(bus.tryEmit(TestEvent.Signal))
    }
}
