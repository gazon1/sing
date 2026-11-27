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
import kotlin.concurrent.atomics.AtomicBoolean
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
 * 3. **Try-emit never blocks.** [tryEmit] returns `false` when the event could not be
 *    handed over right now or the bus is closed, and `true` otherwise. It never counts
 *    as a discard in [droppedAfterClose], which keeps its single meaning: a *suspending*
 *    [emit] on a closed bus. A refusal on a live bus is real loss, so the first one per
 *    bus is logged at `warn` — otherwise it is recorded nowhere, because every call site
 *    ignores the returned flag. On a rendezvous bus the refusal means "no receiver was
 *    waiting" and the message says so, rather than claiming a buffer is full.
 * 4. **Close is graceful, not a cut.** After [close] the events already in the buffer
 *    are still delivered and the collecting flow completes once they are drained. A
 *    send that was already suspended when the close happened is **not** failed and
 *    **not** counted as a discard: `BufferedChannel.close()` cancels the waiting
 *    *receivers* (`cancelSuspendedReceiveRequests`) and leaves the waiting *senders*
 *    alone — only `cancel()` tears senders down (`removeUnprocessedElements`). So the
 *    pending send keeps its slot and completes normally, and its event is delivered as
 *    soon as a receiver frees capacity. Only a *new* [emit] after the close is
 *    discarded. A pending send therefore stays suspended until a receiver drains the
 *    buffer or the emitter is cancelled; closing the bus does not release it.
 *    **`close()` and `cancel()` are opposite here, and the difference is the whole
 *    guarantee**: cancelling a channel fails a suspended send with
 *    `CancellationException` ("Channel was cancelled"), while closing it does not. The
 *    bus only ever closes — see [EventBus.close].
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
 * @param log Kermit logger, defaulted so `MviViewModel` and every test construct the bus
 *   without knowing a log tag exists. Both outcomes are logged rather than only counted:
 *   a counter is read by whoever is already looking, a log line is read by whoever is
 *   looking *now*. Injectable for the same reason [crashReporter] is — the severity of the
 *   full-buffer line is part of the contract (it has to survive the release filter), and
 *   only a test with a captured writer can prove which one was used.
 * @see MviEvent
 */
@Stable
// The stdlib's atomic types are still marked experimental, so the opt-in is here rather
// than pushed onto every caller that constructs an event bus. `AtomicInt` and
// `AtomicBoolean` are the multiplatform names for what are
// `java.util.concurrent.atomic.AtomicInteger`/`AtomicBoolean` on JVM and Android, so the
// state is the same object with a name that also means something on a target without
// `java.util.concurrent`; `incrementAndFetch` and `compareAndSet` are the stdlib's
// spellings of `incrementAndGet` and `compareAndSet`.
@OptIn(ExperimentalAtomicApi::class)
class EventBus<E : MviEvent>(
    capacity: Int = Channel.BUFFERED,
    // Never read in production on purpose: #141 makes "no report on drop-after-close"
    // the contract, and only an injectable reporter lets a test prove an absence.
    // The suppression is for that seam, not to hide a missed call site.
    @Suppress("UnusedPrivateProperty") // see the line above: the unread property IS the guard
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    private val log: Logger = Logger.withTag("EventBus"),
) {
    private val _channel = Channel<E>(capacity)

    /**
     * Whether this bus has no buffer to fill.
     *
     * [Channel.RENDEZVOUS] is capacity `0`, and it is the only capacity for which
     * "full" is a false description: a `trySend` there fails because no receiver is
     * waiting, not because a buffer is occupied. The other negative capacities cannot
     * fail on `trySend` at all (`UNLIMITED`/`CONFLATED` accept everything), so `0` is
     * the whole distinction. Worth knowing because the message is read by someone
     * diagnosing a missing event, and a wrong cause sends them the wrong way.
     */
    private val isRendezvous: Boolean = capacity == Channel.RENDEZVOUS

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
     * Whether the one refusal warning has already been written.
     *
     * A guard, not a counter: the number of refusals is not a fact anyone acts on, only
     * whether the reader was told once. `compareAndSet(false, true)` makes "first one
     * wins" the whole operation, with no count to maintain.
     *
     * Not exposed: the only production bus is `MviViewModel`'s private one, so a public
     * getter would have no production reader — state added to justify itself.
     *
     * The suppression is deliberately permanent rather than rate-limited. A bus that
     * refused once and later hits a sustained overload logs nothing for the second
     * episode. A time-window limiter was considered and left out: it needs a clock the
     * bus does not have, and one line per ViewModel lifetime is enough to point a reader
     * at the code without turning a flood into a slower flood.
     */
    private val hasLoggedRefusal = AtomicBoolean(false)

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
     * @return `true` if the event was accepted, `false` if it could not be handed over
     *   right now or the bus is closed. A refused send is not a discard and is not
     *   counted in [droppedAfterClose] — only a suspending [emit] on a closed bus is.
     *
     * A refusal has two causes with opposite diagnoses, and only one is logged: a
     * **closed** bus is a lifecycle race that [emit] already absorbs, while a refusal on
     * a live bus is real loss — the event is gone and no counter anywhere receives it.
     *
     * "Real loss" is the accurate framing, not "full": on a [Channel.RENDEZVOUS] bus
     * (capacity `0`) the send fails because no receiver is waiting, and there is no
     * buffer to fill. The log message says which of the two happened, because a reader
     * who is handed the wrong cause looks in the wrong place.
     *
     * The severity is not incidental. The release build filters below `Warn`
     * (`applyGlobalSeverity`), so a `debug` line — which is what [emit]'s discard
     * uses — does not exist in the build where a missing snackbar would be reported.
     * One line per bus, not per event: see [hasLoggedRefusal] for that trade-off.
     *
     * The message reports only what is observed. Producers outrunning the collector
     * does not establish that the collector has stopped.
     */
    fun tryEmit(event: E): Boolean {
        val result = _channel.trySend(event)
        if (result.isSuccess) return true
        // A closed bus is the lifecycle race `emit` already absorbs: expected at
        // teardown, so it must not become a production warning.
        if (result.isClosed) return false
        if (hasLoggedRefusal.compareAndSet(false, true)) {
            // "Full" would be a lie on a rendezvous bus: there is no buffer, and the
            // refusal means no receiver was waiting. Two messages, because a reader who
            // trusts a wrong cause looks in the wrong place.
            log.w {
                if (isRendezvous) {
                    "EventBus refused an event; no receiver was waiting"
                } else {
                    "EventBus buffer full; refused an event"
                }
            }
        }
        return false
    }

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
