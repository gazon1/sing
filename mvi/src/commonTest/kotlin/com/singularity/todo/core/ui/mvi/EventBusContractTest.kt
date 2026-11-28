@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.ui.mvi

import com.singularity.todo.core.ui.EventBus
import com.singularity.todo.core.ui.MviLogLevel
import kotlinx.coroutines.channels.Channel
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
    fun `a full-buffer refusal is logged once, at warn, so it survives the release filter`() =
        runTest {
            // The point of the line is the *build it is read in*: release filters below
            // Warn, so a debug line — what the closed-bus discard uses — would not exist
            // where a silently missing snackbar gets reported. Once per bus, because a
            // buffer that stays full must not turn every refusal into a flood.
            val diagnostics = RecordingMviDiagnostics()
            val bus = EventBus<TestEvent>(capacity = 1, diagnostics = diagnostics)

            bus.tryEmit(TestEvent.Signal) // fills the buffer
            repeat(3) { bus.tryEmit(TestEvent.Signal) }

            val lines = diagnostics.lines
            assertEquals(1, lines.size, "one line per bus, not one per refusal")
            assertEquals(MviLogLevel.Warn, lines.single().first, "warn is what release keeps")
            assertTrue(
                "buffer full" in lines.single().second,
                "the message must name what happened: ${lines.single().second}",
            )
        }

    @Test
    fun `a rendezvous bus refuses because nobody is waiting, and says so rather than claiming fullness`() =
        runTest {
            // Channel.RENDEZVOUS is capacity 0: there is no buffer, so trySend fails
            // because no receiver is parked. Calling that "buffer full" would send a
            // reader looking for a slow collector that does not exist.
            val diagnostics = RecordingMviDiagnostics()
            val bus = EventBus<TestEvent>(capacity = Channel.RENDEZVOUS, diagnostics = diagnostics)

            assertFalse(bus.tryEmit(TestEvent.Signal), "nobody is waiting to receive it")

            val (level, message) = diagnostics.lines.single()
            assertEquals(MviLogLevel.Warn, level)
            assertTrue(
                "receiver" in message,
                "a rendezvous refusal must name the real cause: $message",
            )
            assertFalse(
                "buffer full" in message,
                "there is no buffer on this bus: $message",
            )
        }

    @Test
    fun `a buffered bus names the buffer, not the receiver`() =
        runTest {
            // The other half of the distinction: on a real buffer the refusal *is*
            // about capacity, and the message must not blame a missing receiver.
            val diagnostics = RecordingMviDiagnostics()
            val bus = EventBus<TestEvent>(capacity = 1, diagnostics = diagnostics)

            bus.tryEmit(TestEvent.Signal) // fills the buffer
            bus.tryEmit(TestEvent.Signal)

            val (level, message) = diagnostics.lines.single()
            assertEquals(MviLogLevel.Warn, level)
            assertTrue(
                "buffer full" in message,
                "a buffered refusal must name the buffer: $message",
            )
        }

    @Test
    fun `a refusal after close is not logged, because teardown is not a warning`() =
        runTest {
            // The distinction the whole change rests on: a closed bus is an expected
            // lifecycle race, a full buffer is real loss. Turning normal teardown into
            // warn lines would make the signal indistinguishable from noise — exactly
            // what happened to the crash-reporting channel (#141).
            val diagnostics = RecordingMviDiagnostics()
            val bus = EventBus<TestEvent>(diagnostics = diagnostics)
            bus.close()

            assertFalse(bus.tryEmit(TestEvent.Signal), "A closed bus accepts nothing")
            assertEquals(0, bus.droppedAfterClose, "Only a suspending emit counts as a discard")
            assertTrue(
                diagnostics.lines.none { it.first == MviLogLevel.Warn },
                "a closed bus must not warn: ${diagnostics.lines}",
            )
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
            val diagnostics = RecordingMviDiagnostics()
            val bus = EventBus<TestEvent>(diagnostics = diagnostics)
            bus.close()

            bus.emit(TestEvent.Signal)

            assertEquals(1, bus.droppedAfterClose, "The discard is counted")
            assertTrue(
                diagnostics.reports.isEmpty(),
                "A drop after close is a lifecycle race, not a defect, so it must not reach the " +
                    "reporting layer: ${diagnostics.reports}",
            )
        }
}
