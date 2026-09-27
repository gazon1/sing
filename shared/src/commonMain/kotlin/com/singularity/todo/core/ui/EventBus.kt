package com.singularity.todo.core.ui

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

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
class EventBus<E : MviEvent>(capacity: Int = Channel.BUFFERED) {
    private val _channel = Channel<E>(capacity)
    val flow: Flow<E> = _channel.receiveAsFlow()

    suspend fun emit(event: E) = _channel.send(event)

    fun tryEmit(event: E): Boolean = _channel.trySend(event).isSuccess

    fun close() = _channel.close()
}
