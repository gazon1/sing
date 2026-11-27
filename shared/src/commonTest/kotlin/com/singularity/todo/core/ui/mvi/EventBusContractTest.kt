@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.ui.mvi

import com.singularity.todo.core.ui.EventBus
import com.singularity.todo.test.fakes.RecordingCrashReportingPort
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The delivery and shutdown contract of [EventBus], spelled out as tests.
 *
 * The bus is a [kotlinx.coroutines.channels.Channel] exposed as a flow, so most of
 * what it guarantees is the channel's guarantee — but the guarantee is only useful
 * if it is written down and can be checked. These tests pin down what the channel
 * gives us for free, so a future change to the bus (a different capacity, a
 * different close policy) has to break a test instead of a screen.
 *
 * No wall-clock time anywhere: every "it is suspended" assertion is a check that a
 * job did not complete after the scheduler ran everything it could.
 */
@Tag("fast")
class EventBusContractTest {
    @Test
    fun `one collector receives every event in order`() =
        runTest {
            val bus = EventBus<TestEvent>()
            val received = mutableListOf<TestEvent>()

            val collector = launch { bus.flow.collect { received += it } }
            advanceUntilIdle()

            bus.emit(TestEvent.Text("first"))
            bus.emit(TestEvent.Signal)
            advanceUntilIdle()

            assertEquals<List<TestEvent>>(listOf(TestEvent.Text("first"), TestEvent.Signal), received)
            collector.cancel()
        }

    @Test
    fun `two collectors split the events instead of each seeing all of them`() =
        runTest {
            // The single-consumer contract: a channel hands each element to exactly one
            // receiver, so a second collector takes a *share* of the events. A screen that
            // collects the same bus twice races for every event — see ADR
            // 2026-10-22-an-event-bus-has-one-consumer.
            val bus = EventBus<TestEvent>()
            val first = mutableListOf<TestEvent>()
            val second = mutableListOf<TestEvent>()

            val a = launch { bus.flow.collect { first += it } }
            val b = launch { bus.flow.collect { second += it } }
            advanceUntilIdle()

            repeat(3) { bus.emit(TestEvent.Text("e$it")) }
            advanceUntilIdle()

            val emitted = listOf(TestEvent.Text("e0"), TestEvent.Text("e1"), TestEvent.Text("e2"))
            val all = first + second
            assertEquals(3, all.size, "Every event is delivered exactly once")
            assertEquals(3, all.distinct().size, "No event is delivered to both collectors")
            assertEquals(
                emitted.toSet(),
                all.toSet(),
                "The collectors received the emitted events, not just some events",
            )
            a.cancel()
            b.cancel()
        }

    @Test
    fun `buffer capacity is honoured and the next emit suspends`() =
        runTest {
            val bus = EventBus<TestEvent>(capacity = 2)

            bus.emit(TestEvent.Signal)
            bus.emit(TestEvent.Text("fills the buffer"))
            // Both sends fit: the buffer holds exactly `capacity` elements.

            val blocked = launch { bus.emit(TestEvent.Text("one too many")) }
            advanceUntilIdle()

            assertFalse(blocked.isCompleted, "The third emit suspends while the buffer is full")
            assertEquals(0, bus.droppedAfterClose, "Backpressure is not a discard")
            blocked.cancel()
        }

    @Test
    fun `tryEmit returns false when the buffer is full`() =
        runTest {
            val bus = EventBus<TestEvent>(capacity = 1)

            assertTrue(bus.tryEmit(TestEvent.Signal), "The first event fits")
            assertFalse(bus.tryEmit(TestEvent.Signal), "The second event does not fit")
            assertEquals(0, bus.droppedAfterClose, "A refused tryEmit is not a discard")
        }

    @Test
    fun `tryEmit returns false after close`() =
        runTest {
            val bus = EventBus<TestEvent>()
            bus.close()

            assertFalse(bus.tryEmit(TestEvent.Signal), "A closed bus accepts nothing")
            assertEquals(0, bus.droppedAfterClose, "Only a suspending emit counts as a discard")
        }

    @Test
    fun `cancelling a suspended sender stops it without counting a discard`() =
        runTest {
            val bus = EventBus<TestEvent>(capacity = 1)
            bus.emit(TestEvent.Signal) // fills the buffer

            val sender = launch { bus.emit(TestEvent.Text("blocked")) }
            advanceUntilIdle()
            assertFalse(sender.isCompleted, "The sender is suspended on a full buffer")

            sender.cancel()
            sender.join()

            assertTrue(sender.isCancelled, "The cancelled sender ends as cancelled")
            assertEquals(0, bus.droppedAfterClose, "A cancellation is not a closed-bus discard")
        }

    @Test
    fun `a cancelled channel fails a suspended sender, which close does not`() =
        runTest {
            // The guarantee above is *specifically* about `close()`, and it is easy to
            // state backwards: cancelling a channel resumes a suspended sender with
            // `CancellationException` ("Channel was cancelled"), while closing it does
            // not. Pinned here against the raw channel, because the doc on
            // `EventBus.close` rests on that distinction — if a kotlinx upgrade ever
            // made close() tear down senders, this fails and the KDoc is wrong.
            val channel = kotlinx.coroutines.channels.Channel<TestEvent>(capacity = 1)
            channel.send(TestEvent.Signal) // fills the buffer

            var cancelFailure: Throwable? = null
            val sender =
                launch {
                    try {
                        channel.send(TestEvent.Text("pending")) // never delivered: the receiver is gone
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        cancelFailure = e
                    }
                }
            advanceUntilIdle()
            assertFalse(sender.isCompleted, "The sender waits on a full buffer")

            channel.cancel()
            advanceUntilIdle()

            assertTrue(
                sender.isCompleted,
                "cancel() tears a suspended sender down — unlike close(), which leaves it pending",
            )
            assertTrue(
                cancelFailure != null,
                "the pending send fails with a CancellationException when the channel is cancelled",
            )
        }

    @Test
    fun `close is graceful and a send that was already in flight is still delivered`() =
        runTest {
            // `close()` is graceful, not a cut — and it is graceful in a specific way.
            //
            // In kotlinx-coroutines' buffered channel a close resumes the *waiting
            // receivers* and leaves the *waiting senders* alone: the suspended senders
            // are the ones `cancel()` tears down (`BufferedChannel.completeClose` vs
            // `completeCancel`). A send that was already suspended when the bus was
            // closed therefore keeps its slot, stays pending, and completes normally —
            // its event is delivered as soon as a receiver frees capacity.
            //
            // That is why the close below is *not* a discard: `droppedAfterClose` stays
            // at zero, and the pending send is not turned into a failed one.
            val bus = EventBus<TestEvent>(capacity = 1)
            bus.emit(TestEvent.Signal) // fills the buffer

            val sender = launch { bus.emit(TestEvent.Text("in flight")) }
            advanceUntilIdle()
            assertFalse(sender.isCompleted, "The sender is suspended on a full buffer")

            bus.close()
            advanceUntilIdle()

            assertFalse(
                sender.isCompleted,
                "close() does not fail a pending send: the sender keeps waiting for room",
            )
            assertFalse(sender.isCancelled, "close() does not cancel a pending send")
            assertEquals(0, bus.droppedAfterClose, "A pending send is not a discard")

            val received = mutableListOf<TestEvent>()
            val collector = launch { bus.flow.collect { received += it } }
            advanceUntilIdle()

            assertEquals<List<TestEvent>>(
                listOf(TestEvent.Signal, TestEvent.Text("in flight")),
                received,
                "The buffered event and the in-flight one are both delivered after the close",
            )
            assertTrue(sender.isCompleted, "The in-flight sender finished once the room freed")
            assertEquals(0, bus.droppedAfterClose, "A delivered event is not a discard")
            collector.cancel()
        }

    @Test
    fun `draining the buffer resumes a suspended sender`() =
        runTest {
            // Backpressure in the other direction: a suspended sender is not lost either.
            // It completes — and its event arrives exactly once, in order — as soon as a
            // receiver frees capacity.
            val bus = EventBus<TestEvent>(capacity = 1)
            bus.emit(TestEvent.Signal) // fills the buffer

            val sender = launch { bus.emit(TestEvent.Text("waiting for room")) }
            advanceUntilIdle()
            assertFalse(sender.isCompleted, "The sender is suspended on a full buffer")

            val received = mutableListOf<TestEvent>()
            val collector = launch { bus.flow.collect { received += it } }
            advanceUntilIdle()

            assertTrue(sender.isCompleted, "Draining the buffer resumes the suspended sender")
            assertEquals<List<TestEvent>>(
                listOf(TestEvent.Signal, TestEvent.Text("waiting for room")),
                received,
                "Both events arrive exactly once and in order",
            )
            assertEquals(0, bus.droppedAfterClose, "Backpressure is not a discard")
            collector.cancel()
        }

    @Test
    fun `buffered events are still delivered and the collector completes after close`() =
        runTest {
            val bus = EventBus<TestEvent>(capacity = 4)
            bus.emit(TestEvent.Text("first"))
            bus.emit(TestEvent.Text("second"))

            bus.close()

            val received = mutableListOf<TestEvent>()
            bus.flow.collect { received += it } // returns when the closed channel is drained

            assertEquals<List<TestEvent>>(
                listOf(TestEvent.Text("first"), TestEvent.Text("second")),
                received,
            )
        }

    @Test
    fun `with no collector the events buffer up and the next emit suspends`() =
        runTest {
            val bus = EventBus<TestEvent>(capacity = 2)

            bus.emit(TestEvent.Signal)
            bus.emit(TestEvent.Text("waiting for a collector"))

            val blocked = launch { bus.emit(TestEvent.Text("one too many")) }
            advanceUntilIdle()

            assertFalse(blocked.isCompleted, "Without a collector the buffer fills and emit suspends")
            blocked.cancel()
        }

    @Test
    fun `an emit after close is discarded and never reported as a crash`() =
        runTest {
            val reporter = RecordingCrashReportingPort()
            val bus = EventBus<TestEvent>(crashReporter = reporter)
            bus.close()

            bus.emit(TestEvent.Signal)

            assertEquals(1, bus.droppedAfterClose, "The discard is counted")
            assertTrue(
                reporter.reports.isEmpty(),
                "A drop after close is a lifecycle race, not a defect, so it must not reach the " +
                    "reporting layer: ${reporter.reports}",
            )
        }
}
