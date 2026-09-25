package com.singularity.todo.core.ui

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Read-only base for ViewModels that own a [MutableStateFlow] of state.
 *
 * Provides [state] (public read-only [StateFlow]), [currentState] (synchronous snapshot),
 * [setState] (direct replacement), and [update] (protected reducer).
 * Subclasses must NOT call [addCloseable] — [StatefulViewModel] does it in its own init.
 *
 * Example:
 * ```
 * class TagsViewModel(
 *     private val tagRepo: TagsRepository,
 *     scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
 * ) : StatefulViewModel<TagsUiState>(TagsUiState.Loading, scope) {
 *     // no addCloseable(scope) needed here — parent handles it
 *     // ...
 * }
 * ```
 *
 * @param S The concrete UI state type (e.g. `TagsUiState`).
 * @param initialState The initial state value.
 * @param scope Coroutine scope for collecting flows and launching background work.
 */
abstract class StatefulViewModel<S>(
    initialState: S,
    scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {

    init {
        addCloseable(scope)
    }

    private val _state = MutableStateFlow(initialState)

    /** Public read-only state. */
    val state: StateFlow<S> = _state.asStateFlow()

    /** Synchronous snapshot of the current state. Use inside reducers and intent handlers. */
    protected val currentState: S get() = _state.value

    /**
     * Directly replaces the current state with [newState].
     * Prefer [update] for reducer-style mutations.
     */
    protected fun setState(newState: S) {
        _state.value = newState
    }

    /**
     * Updates state by applying [reducer] to the current value.
     *
     * Uses [kotlinx.coroutines.flow.MutableStateFlow.update] internally (CAS loop).
     * For simple direct assignment, use [setState] instead.
     */
    protected fun update(reducer: (S) -> S) {
        _state.update(reducer)
    }
}
