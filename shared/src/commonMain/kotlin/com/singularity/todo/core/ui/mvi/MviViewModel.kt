package com.singularity.todo.core.ui.mvi

import androidx.lifecycle.ViewModel
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
 *     init { addCloseable(scope) }
 *
 *     override fun onIntent(intent: TagsIntent) {
 *         when (intent) {
 *             is TagsIntent.Delete -> scope.launch { delete(intent.id) }
 *         }
 *     }
 *
 *     private suspend fun delete(id: TagId) {
 *         tagRepo.delete(id).onFailure { emit(TagsUiEvent.ShowError(it.message ?: "Error")) }
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
     * Delegates to [kotlinx.coroutines.flow.MutableStateFlow.update].
     * For VMs requiring atomic read-modify-write, use [StateStrategy.Atomic] —
     * available from MR-3 onwards.
     *
     * For simple direct assignment, use `_state.value = newValue` instead.
     */
    protected suspend fun updateState(transform: (S) -> S) {
        update(transform)
    }

    /**
     * Type-safe state update that only runs [transform] when the current state is of type [T].
     *
     * Eliminates `?: return` guards in sealed state hierarchies.
     * @see updateState
     */
    protected suspend inline fun <reified T : S> updateStateAs(
        noinline transform: (T) -> S,
    ) {
        val current = state.value
        if (current is T) {
            updateState { transform(current) }
        }
    }

    /**
     * Called by the screen layer to dispatch an intent into the MVI loop.
     */
    abstract fun onIntent(intent: I)
}
