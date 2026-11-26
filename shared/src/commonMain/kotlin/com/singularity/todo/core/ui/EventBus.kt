package com.singularity.todo.core.ui

import androidx.compose.runtime.Stable
import co.touchlab.kermit.Logger
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Channel-backed one-shot event bus.
 *
 * Default buffer capacity is [Channel.BUFFERED] — the library default of 64 elements,
 * unless the runtime overrides `kotlinx.coroutines.buffer.default.capacity`.
 * This is the correct default for ViewModels where a single collector (typically
 * [NotificationHost]) handles all events.
 *
 * For multi-subscriber broadcast scenarios, collect a shared flow directly.
 *
 * ## Guarantees
 *
 * 1. **One consumer.** The bus is a channel, so it has exactly one consumer: two
 *    collectors of [flow] *split* the events — each event goes to one of them, never
 *    to both. A screen that collects the same bus twice races for every event; see
 *    ADR `2026-10-22-an-event-bus-has-one-consumer`.
 * 2. **No loss, backpressure instead.** [emit] suspends while the buffer is full
 *    instead of dropping, so a live consumer loses nothing — the sender waits.
 * 3. **Try-emit never blocks.** [tryEmit] returns `false` when the buffer is full or
 *    the bus is closed, and `true` otherwise. It never counts as a discard.
 * 4. **Close is graceful, not a cut.** After [close] the events already in the buffer
 *    are still delivered and the collecting flow completes once they are drained. A
 *    send that was already suspended when the close happened is **not** failed and
 *    **not** counted as a discard: a channel close resumes the waiting *receivers*,
 *    while the waiting *senders* are the ones `cancel()` tears down, so the pending
 *    send keeps its slot and completes normally — its event is delivered as soon as
 *    a receiver frees capacity. Only a *new* [emit] after the close is discarded.
 *    A pending send therefore stays suspended until a receiver drains the buffer or
 *    the emitter is cancelled; closing the bus does not release it.
 * 5. **Send after close is absorbed.** [emit] on a closed bus discards the event,
 *    counts it in [droppedAfterClose], and never reports it as a crash — a discard is
 *    a lifecycle race, not a defect.
 * 6. **Cancellation is not a discard.** Cancelling a suspended [emit] propagates the
 *    [kotlinx.coroutines.CancellationException] and is not counted in
 *    [droppedAfterClose].
 *
 * @param capacity Buffer capacity. Defaults to [Channel.BUFFERED].
 * @param crashReporter Deliberately **never** called for the drop-after-close case
 *   (#141: a discard is a lifecycle race, not a defect, and a report per teardown is
 *   noise on a channel that must mean "something is wrong"). The parameter exists so a
 *   test can inject a [com.singularity.todo.test.fakes.RecordingCrashReportingPort]
 *   and assert `reports.isEmpty()` — the absence of a report is the contract, and only
 *   an injectable reporter can prove it.
 * @see MviEvent
 */
@Stable
// The stdlib's atomic types are still marked experimental, so the opt-in is here rather
// than pushed onto every caller that constructs an event bus. `AtomicInt` is the
// multiplatform name for what is `java.util.concurrent.atomic.AtomicInteger` on JVM and
// Android, so the counter is the same object with a name that also means something on a
// target without `java.util.concurrent`; `incrementAndFetch` is the stdlib's spelling of
// `incrementAndGet`.
@OptIn(ExperimentalAtomicApi::class)
class EventBus<E : MviEvent>(
    capacity: Int = Channel.BUFFERED,
    // Never read in production on purpose: #141 makes "no report on drop-after-close"
    // the contract, and only an injectable reporter lets a test prove an absence.
    // The suppression is for that seam, not to hide a missed call site.
    @Suppress("UnusedPrivateProperty") // see the line above: the unread property IS the guard
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
) {
    private val _channel = Channel<E>(capacity)

    /**
     * The events, as a flow. **One consumer**: collecting it twice splits the events
     * between the two collectors instead of duplicating them. The flow completes when
     * the bus is closed and the buffer is drained.
     */
    val flow: Flow<E> = _channel.receiveAsFlow()

    /**
     * Events discarded because the bus was already closed. Observable on purpose: an
     * event that never arrived is otherwise indistinguishable from one that was never
     * sent, and this counter is what tells the two apart when someone reports it.
     */
    val droppedAfterClose: Int get() = droppedAfterCloseCount.load()

    private val droppedAfterCloseCount = AtomicInt(0)

    /**
     * Kermit logger, defaulted so `MviViewModel` and every test construct the bus without
     * knowing a log tag exists. The drop is logged rather than only counted: a counter is
     * read by whoever is already looking, a log line is read by whoever is looking *now*.
     */
    private val log: Logger = Logger.withTag("EventBus")

    /**
     * Sends [event], or discards it if the bus is closed.
     *
     * Sends **suspend** while the buffer is full: backpressure, not loss. A live
     * consumer therefore never misses an event — the sender waits for room.
     *
     * A closed bus means the owning screen is gone, which is a lifecycle race rather
     * than a defect: work on the ViewModel scope can still be in flight when
     * [close] runs. Throwing here escalated that race to an uncaught exception, and once
     * the background failure handler existed it turned into a non-fatal report per
     * occurrence — noise on a channel that is supposed to mean "something is wrong".
     * Such a discard is counted in [droppedAfterClose] and never reported.
     *
     * Only the *closed* case is absorbed; a full buffer still applies backpressure, and
     * the caller's own cancellation still propagates (see [tryEmit] for the
     * non-suspending variant).
     */
    suspend fun emit(event: E) {
        try {
            _channel.send(event)
        } catch (cancelled: CancellationException) {
            // Cancellation is not the closed-bus case. `send` rethrows the collecting
            // coroutine's own CancellationException when the *emitter* is cancelled, and
            // absorbing that would leave a cancelled coroutine running to completion and
            // break structured concurrency. It must propagate before the closed-channel
            // arm is reached — hence the ordering.
            throw cancelled
        } catch (closed: ClosedSendChannelException) {
            // Closed-bus case: a lifecycle race, not a defect. Count and log — but do not
            // report as a crash, because a drop that is expected on a closed channel is
            // not a defect. The test enforces this: `reports.isEmpty()` after a drop.
            val dropped = droppedAfterCloseCount.incrementAndFetch()
            log.d(closed) { "EventBus closed, dropped event #$dropped" }
        }
    }

    /**
     * Sends [event] without suspending.
     *
     * @return `true` if the event was accepted, `false` if the buffer is full or the
     *   bus is closed. A refused send is not a discard and is not counted in
     *   [droppedAfterClose] — only a suspending [emit] on a closed bus is.
     */
    fun tryEmit(event: E): Boolean = _channel.trySend(event).isSuccess

    /**
     * Closes the bus.
     *
     * The close is graceful: the events already in the buffer are still delivered, the
     * collecting flow completes afterwards, and a send that was already suspended when
     * the close happened is left alone — a channel close resumes the waiting receivers,
     * not the waiting senders (`cancel()` is what tears those down). Such a pending
     * send keeps its slot and completes normally once a receiver frees capacity, and it
     * is not counted in [droppedAfterClose].
     *
     * A later [emit] is discarded the same way as any other send after the close —
     * counted in [droppedAfterClose], never reported.
     */
    fun close() = _channel.close()
}
