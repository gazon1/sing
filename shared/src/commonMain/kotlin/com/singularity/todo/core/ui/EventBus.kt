package com.singularity.todo.core.ui

import androidx.compose.runtime.Stable
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Channel-backed one-shot event bus.
 *
 * Default buffer capacity is [Channel.BUFFERED] (default 64 elements).
 * Channel guarantees no event loss for a single consumer — this is the correct
 * default for ViewModels where a single collector (typically [NotificationHost])
 * handles all events.
 *
 * For multi-subscriber broadcast scenarios, collect a shared flow directly.
 *
 * @param capacity Buffer capacity. Defaults to [Channel.BUFFERED].
 * @see MviEvent
 */
@Stable
class EventBus<E : MviEvent>(capacity: Int = Channel.BUFFERED) {
    private val _channel = Channel<E>(capacity)
    val flow: Flow<E> = _channel.receiveAsFlow()

    /**
     * Events discarded because the bus was already closed. Observable on purpose: an
     * event that never arrived is otherwise indistinguishable from one that was never
     * sent, and this counter is what tells the two apart when someone reports it.
     */
    val droppedAfterClose: Int get() = droppedAfterCloseCount.get()

    private val droppedAfterCloseCount = AtomicInteger(0)

    /**
     * Kermit logger, defaulted so `MviViewModel` and every test construct the bus without
     * knowing a log tag exists. The drop is logged rather than only counted: a counter is
     * read by whoever is already looking, a log line is read by whoever is looking *now*.
     */
    private val log: Logger = Logger.withTag("EventBus")

    /**
     * Sends [event], or discards it if the bus is closed.
     *
     * A closed bus means the owning screen is gone, which is a lifecycle race rather
     * than a defect: work on the ViewModel scope can still be in flight when
     * [close] runs. Throwing here escalated that race to an uncaught exception, and once
     * the background failure handler existed it turned into a non-fatal report per
     * occurrence — noise on a channel that is supposed to mean "something is wrong".
     *
     * Sending still suspends when the buffer is full, so the no-loss guarantee for a
     * live consumer is unchanged. Only the *closed* case is absorbed; a full buffer
     * still applies backpressure.
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
            val dropped = droppedAfterCloseCount.incrementAndGet()
            // `closed` is logged rather than ignored so the line says *why* the event went
            // missing, not just that it did.
            log.d(closed) { "EventBus closed, dropped event #$dropped" }
        }
    }

    fun tryEmit(event: E): Boolean = _channel.trySend(event).isSuccess

    fun close() = _channel.close()
}
