package com.singularity.todo.core.ui

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.toMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Base ViewModel for the MVI loop: a single [StateFlow] of UI state, a one-shot
 * [EventBus] for effects the screen must act on exactly once, and an [onIntent] entry point.
 *
 * ## State has exactly two entry points
 * [updateState] (apply a reducer, CAS) and [setState] (direct replacement). Both are
 * `final` on purpose: an `open` writer that a subclass can silently override is how
 * the state stream and the event bus drift apart.
 *
 * ## Errors
 * [catchTo] is the primitive for "run this, route a failure somewhere"; [emitError] is
 * the common case where a failure becomes a one-shot event. [catchTo]'s [onError] is
 * `suspend` so callers can [emit] straight from it — the non-suspend variants forced
 * every call site to wrap the emit in a nested `launch`.
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
 *     // no addCloseable(scope) needed here — the base init does it
 *
 *     override fun onIntent(intent: TagsIntent) {
 *         when (intent) {
 *             is TagsIntent.Delete -> emitError("Delete failed", TagsUiEvent::ShowError) {
 *                 tagRepo.delete(intent.id)
 *             }
 *         }
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
) : ViewModel() {

    init {
        addCloseable(scope)
    }

    /**
     * Scope for collectors and intent handlers. Deliberately not `open` — the eight
     * subclasses that overrode it only ever assigned the same `scope` back to it.
     */
    protected val vmScope: AutoCloseableCoroutineScope = scope

    private val _state = MutableStateFlow(initialState)

    /** Public read-only state. The single way a screen observes this ViewModel. */
    val state: StateFlow<S> = _state.asStateFlow()

    /** Synchronous snapshot. Use inside reducers and intent handlers. */
    protected val currentState: S get() = _state.value

    /**
     * Updates state by applying [transform] to the current value.
     *
     * Non-suspending, backed by a CAS loop — safe when more than one coroutine
     * writes concurrently. For a value you have already computed, use [setState].
     */
    protected fun updateState(transform: (S) -> S) {
        _state.update(transform)
    }

    /**
     * Directly replaces the current state with [newState].
     * @see updateState
     */
    protected fun setState(newState: S) {
        _state.value = newState
    }

    private val _events = EventBus<E>(extraEventCapacity)

    /** Flow of one-shot events. Collect in your screen's effect layer. */
    val events: Flow<E> get() = _events.flow

    /** Emits a one-shot event. Suspends until the channel accepts it. */
    protected suspend fun emit(event: E) = _events.emit(event)

    /**
     * Tries to emit a one-shot event without suspending.
     * Returns `true` if the event was sent, `false` if the buffer is full.
     */
    protected fun tryEmit(event: E): Boolean = _events.tryEmit(event)

    /**
     * Runs [block] on [vmScope]; on `Result.failure` hands `toMessage(errorLabel)` to [onError].
     *
     * A thrown exception is converted the same way — `require(...)`, `getOrThrow()`
     * and throwing repository calls land in [onError] instead of propagating out of
     * the coroutine. Before this guard, a throw escaped `vmScope.launch`: on Android
     * that is an uncaught exception and kills the process; in tests it cancels a
     * scope shared with the test host and silently freezes every collector on it
     * (discovered when renaming a missing tag threw `AppError.NotFound`).
     *
     * Use when the failure lands in state (`catchTo(label, { msg -> updateState { … } })`).
     * For the one-shot-event case prefer [emitError].
     */
    protected fun catchTo(errorLabel: String, onError: suspend (String) -> Unit, block: suspend () -> Result<*>): Job =
        vmScope.launch {
            runCatching { block() }.fold(
                onSuccess = { result -> result.onFailure { onError(it.toMessage(errorLabel)) } },
                onFailure = { e -> onError(e.toMessage(errorLabel)) },
            )
        }

    /** [catchTo] for the common case: a failure becomes a one-shot [E] built from the message. */
    protected fun emitError(errorLabel: String, errorEvent: (String) -> E, block: suspend () -> Result<*>): Job =
        catchTo(errorLabel, { msg -> emit(errorEvent(msg)) }, block)

    /**
     * Called by the screen layer to dispatch an intent into the MVI loop.
     */
    abstract fun onIntent(intent: I)

    override fun onCleared() {
        _events.close()
        super.onCleared()
    }
}
