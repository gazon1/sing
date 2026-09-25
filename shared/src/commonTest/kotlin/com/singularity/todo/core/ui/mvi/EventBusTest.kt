package com.singularity.todo.core.ui.mvi

import com.singularity.todo.core.ui.EventBus
import com.singularity.todo.core.ui.SharedEventBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    fun `EventBus emits events to collector`() =
        runTest {
            val bus = EventBus<TestEvent>()
            val results = mutableListOf<TestEvent>()

            launch {
                bus.flow.collect { results.add(it) }
            }.let { job ->
                bus.emit(TestEvent.Text("hello"))
                delay(10)
                bus.emit(TestEvent.Signal)
                delay(10)
                job.cancel()
            }

            assertEquals(2, results.size)
            assertTrue(results[0] is TestEvent.Text)
            assertEquals("hello", (results[0] as TestEvent.Text).message)
            assertEquals(TestEvent.Signal, results[1])
        }

    @Test
    fun `EventBus tryEmit returns true when buffer has capacity`() =
        runTest {
            val bus = EventBus<TestEvent>(capacity = 1)
            assertTrue(bus.tryEmit(TestEvent.Signal))
        }

    @Test
    fun `SharedEventBus emits to subscribers`() =
        runTest {
            val bus = SharedEventBus<TestEvent>(extraBufferCapacity = 2)
            val results = mutableListOf<TestEvent>()

            // Start collector first, then emit (SharedEventBus emits to active subscribers)
            launch {
                bus.flow.collect { results.add(it) }
            }.let { job ->
                delay(20) // ensure collector starts
                bus.emit(TestEvent.Text("hello"))
                delay(20)
                bus.emit(TestEvent.Signal)
                delay(20)
                job.cancel()
            }

            assertTrue(results.isNotEmpty(), "should have received at least one event")
            assertTrue(results[0] is TestEvent.Text)
        }
}
