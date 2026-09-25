package com.singularity.todo.core.ui

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Channel-backed one-shot event bus.
 *
 * Default buffer capacity is [Channel.BUFFERED] (default 64 elements).
 * Channel guarantees no event loss for a single consumer — this is the correct
 * default for ViewModels where a single collector (typically [NotificationHost])
 * handles all events.
 *
 * For multi-subscriber broadcast scenarios, use [SharedEventBus] instead.
 *
 * @param capacity Buffer capacity. Defaults to [Channel.BUFFERED].
 * @see MviEvent
 */
class EventBus<E : MviEvent>(capacity: Int = Channel.BUFFERED) {
    private val _channel = Channel<E>(capacity)
    val flow: Flow<E> = _channel.receiveAsFlow()

    suspend fun emit(event: E) = _channel.send(event)

    fun tryEmit(event: E): Boolean = _channel.trySend(event).isSuccess
}

/**
 * SharedFlow-backed one-shot event bus for broadcast scenarios.
 *
 * Use this only when multiple independent collectors need to receive the same event.
 * Note: SharedFlow drops events emitted when there are no active subscribers.
 * If a late subscriber joins, it will NOT receive previously emitted events.
 *
 * @param extraBufferCapacity Additional buffer slots beyond the replay. Defaults to 4.
 * @see MviEvent
 */
class SharedEventBus<E : MviEvent>(extraBufferCapacity: Int = 4) {
    private val _flow = MutableSharedFlow<E>(extraBufferCapacity = extraBufferCapacity)
    val flow: Flow<E> = _flow.asSharedFlow()

    suspend fun emit(event: E) = _flow.emit(event)

    fun tryEmit(event: E): Boolean = _flow.tryEmit(event)
}
