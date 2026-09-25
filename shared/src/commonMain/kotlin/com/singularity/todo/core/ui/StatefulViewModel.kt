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
 * Provides [state] (public read-only [StateFlow]) and [update] (protected reducer).
 * Subclasses must call [addCloseable] in their [init][kotlinx.coroutines.CoroutineScope] block.
 *
 * Example:
 * ```
 * class TagsViewModel(
 *     private val tagRepo: TagsRepository,
 *     scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
 * ) : StatefulViewModel<TagsUiState>(TagsUiState.Loading, scope) {
 *     init { addCloseable(scope) }
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

    // Visible for subclasses that need direct synchronous mutation in non-suspend intent handlers.
    // Triple underscore breaks ktlint's BackingPropertyNaming AND VariableNaming rules.
    @Suppress("VariableNaming", "BackingPropertyNaming")
    protected val __state = MutableStateFlow(initialState)

    /** Public read-only state. */
    val state: StateFlow<S> = __state.asStateFlow()

    /**
     * Updates state by applying [reducer] to the current value.
     *
     * Suspend so the [transform][transform] lambda can contain suspend operations.
     * For simple non-suspend updates (e.g. `update { newState }`), assign directly:
     * `__state.value = newState`.
     */
    protected fun update(transform: (S) -> S) {
        __state.update(transform)
    }
}
