package com.singularity.todo.core.ui

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow

/**
 * Full-featured MVI ViewModel base with event emission and typed state updates.
 *
 * Combines [StatefulViewModel] with [EventBus] for one-shot events and adds
 * [updateState] variants — including a reified overload that eliminates `?: return` guards
 * for sealed state hierarchies.
 *
 * ## Usage
 * ```
 * class TagsViewModel(
 *     private val tagRepo: TagsRepository,
 *     scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
 * ) : MviViewModel<TagsUiState, TagsIntent, TagsUiEvent>(
 *     initialState = TagsUiState.Loading,
 *     scope = scope,
 * ) {
 *     // no addCloseable(scope) needed here — parent handles it
 *
 *     override fun onIntent(intent: TagsIntent) {
 *         when (intent) {
 *             is TagsIntent.Delete -> scope.launch { delete(intent.id) }
 *         }
 *     }
 *
 *     private suspend fun delete(id: TagId) {
 *         tagRepo.delete(id).onFailure { emit(TagsUiEvent.ShowError(it.toMessage())) }
 *     }
 * }
 * ```
 *
 * @param S The UI state type (sealed hierarchy recommended).
 * @param I The intent type (user action sealed hierarchy).
 * @param E The event type (one-shot UI events sealed hierarchy).
 * @param initialState The initial UI state.
 * @param extraEventCapacity Extra buffer capacity for the event [Channel]. Defaults to [Channel.BUFFERED].
 * @param scope Coroutine scope for collecting flows and launching background work.
 */
abstract class MviViewModel<S, I : MviIntent, E : MviEvent>(
    initialState: S,
    extraEventCapacity: Int = Channel.BUFFERED,
    scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : StatefulViewModel<S>(initialState, scope) {

    /** Exposed scope for subclasses launching coroutines in intent handlers. */
    protected open val vmScope: AutoCloseableCoroutineScope = scope

    private val _events = EventBus<E>(extraEventCapacity)

    /** Flow of one-shot UI events. Collect in your screen's effect layer. */
    val events: Flow<E> get() = _events.flow

    /** Emits a one-shot event. Suspends until the channel accepts it. */
    protected suspend fun emit(event: E) = _events.emit(event)

    /**
     * Tries to emit a one-shot event without suspending.
     * Returns `true` if the event was sent, `false` if the buffer is full.
     */
    protected fun tryEmit(event: E): Boolean = _events.tryEmit(event)

    /**
     * Updates state by applying [transform] to the current value.
     *
     * Non-suspending — delegates to [kotlinx.coroutines.flow.MutableStateFlow.update].
     * Override [onStateChanged] to react to state transitions (logging, analytics, etc.).
     *
     * For simple direct replacement (when you already have the full new state),
     * use [setState] instead.
     */
    protected fun updateState(transform: (S) -> S) {
        val old = currentState
        update(transform)
        onStateChanged(old, currentState)
    }

    /**
     * Directly replaces the current state with [newState].
     * Prefer [updateState] for reducer-style mutations.
     * @see updateState
     */
    override fun setState(newState: S) {
        val old = currentState
        super.setState(newState)
        onStateChanged(old, newState)
    }

    /**
     * Type-safe state update that only runs [transform] when the current state is of type [T].
     *
     * Eliminates `?: return` guards in sealed state hierarchies.
     * @see updateState
     */
    protected inline fun <reified T : S> updateStateAs(noinline transform: (T) -> S) {
        val current = currentState
        if (current is T) {
            val old = currentState
            update { transform(current) }
            @Suppress("UNCHECKED_CAST")
            onStateChanged(old, currentState)
        }
    }

    /**
     * Called after every state mutation via [updateState] or [updateStateAs].
     * Override to log state transitions, fire analytics, etc.
     * Default implementation is a no-op.
     */
    protected open fun onStateChanged(old: S, new: S) {}

    /**
     * Called by the screen layer to dispatch an intent into the MVI loop.
     */
    abstract fun onIntent(intent: I)

    override fun onCleared() {
        _events.close()
    }
}
