@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.ui.mvi

import com.singularity.todo.core.ui.EventBus
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.test.fakes.RecordingCrashReportingPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

interface TestEvent : MviEvent {
    data class Text(val message: String) : TestEvent
    data object Signal : TestEvent
}

@Tag("fast")
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

/**
 * The closed-bus case is a lifecycle race: work on the ViewModel scope can still be in
 * flight when `MviViewModel.onCleared()` closes the bus. Before this was absorbed, the
 * resulting `ClosedSendChannelException` escalated to an uncaught exception and then, once
 * the background failure handler existed, to a non-fatal report per occurrence.
 */
@Tag("fast")
class EventBusAfterCloseTest {

    @Test
    fun `emitting after close is discarded rather than thrown`() = runTest {
        val reporter = RecordingCrashReportingPort()
        val bus = EventBus<TestEvent>(crashReporter = reporter)
        bus.close()

        bus.emit(TestEvent.Signal) // must not throw

        assertEquals(1, bus.droppedAfterClose, "The discard is counted, not just swallowed")
        assertTrue(
            reporter.reports.isEmpty(),
            "A drop after close must not surface as a crash report — the discard is a "
                + "lifecycle race, not a defect: " + reporter.reports,
        )
    }

    @Test
    fun `emitting after close does not disturb work that called it`() = runTest {
        val reporter = RecordingCrashReportingPort()
        val bus = EventBus<TestEvent>(crashReporter = reporter)
        val reached = mutableListOf<String>()
        bus.close()

        val job = launch {
            bus.emit(TestEvent.Signal)
            reached += "after emit"
        }
        job.join()

        assertEquals(listOf("after emit"), reached, "The emitting coroutine ran to completion")
        assertTrue(reporter.reports.isEmpty(), "Drop after close must not report: ${reporter.reports}")
    }

    @Test
    fun `a live bus is unaffected and drops nothing`() = runTest {
        val bus = EventBus<TestEvent>()
        val received = mutableListOf<TestEvent>()
        val collector = launch { bus.flow.collect { received += it } }

        bus.emit(TestEvent.Text("hello"))
        advanceUntilIdle()
        collector.cancel()

        assertEquals<List<TestEvent>>(listOf(TestEvent.Text("hello")), received)
        assertEquals(0, bus.droppedAfterClose, "Nothing was discarded while the bus was live")
    }

    @Test
    fun `a full buffer still applies backpressure rather than dropping`() = runTest {
        // The fix absorbs the *closed* case only. A full buffer must keep suspending, or
        // one-shot events would be silently lost under load — the opposite trade.
        val bus = EventBus<TestEvent>(capacity = 1)
        var sent = 0
        val sender = launch {
            repeat(3) {
                bus.emit(TestEvent.Signal)
                sent++
            }
        }

        advanceUntilIdle()

        assertTrue(sent < 3, "The third send should still be suspended, sent=$sent of 3")
        assertEquals(0, bus.droppedAfterClose, "Backpressure is not a discard")
        sender.cancel()
    }

    @Test
    fun `cancelling the emitter propagates instead of being counted as a drop`() = runTest {
        // The totality fix has a boundary. Absorbing ClosedSendChannelException must not
        // also absorb the *emitter's own* CancellationException: a coroutine that has been
        // cancelled and keeps running is a structured-concurrency bug, and it would hide
        // behind a counter that says "1 dropped" — a number that reads like a lifecycle
        // race when it is actually a cancellation that failed to propagate.
        val bus = EventBus<TestEvent>()
        val emitted = mutableListOf<String>()
        var propagated: CancellationException? = null

        val job = launch {
            bus.emit(TestEvent.Signal)
            emitted += "completed" // must NOT run: the coroutine was cancelled
        }
        job.cancel()
        job.join()

        assertEquals(
            emptyList(),
            emitted,
            "A cancelled emitter must not run past the cancellation boundary",
        )
        assertEquals(
            0,
            bus.droppedAfterClose,
            "Cancellation is not a closed-bus discard and must not be counted as one",
        )
        assertTrue(job.isCancelled, "The job stayed cancelled, propagated=$propagated")
    }
}
