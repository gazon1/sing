package com.singularity.todo.core.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Base for draft / editing state that needs undo-style reset.
 *
 * Stores the current draft value and supports [update] (in-place modification)
 * and [reset] (restore to saved checkpoint).
 *
 * Used for editor ViewModels that keep an in-memory copy before persisting,
 * allowing the user to discard changes.
 *
 * ## Usage
 * ```
 * class TaskDraftState(initial: Task? = null) : DraftState<Task?>(initial) {
 *     fun updateTitle(title: String) = update { it?.copy(title = title) }
 *     fun resetTo(task: Task) = reset(task)
 * }
 * ```
 *
 * @param S The draft type (often a nullable domain model, e.g. `Task?`).
 * @param initial The initial draft value.
 */
abstract class DraftState<S>(initial: S) {
    private val _state = MutableStateFlow(initial)

    /** Current draft value. */
    val state: S get() = _state.value

    /** Updates the draft by applying [reducer]. */
    protected fun update(reducer: (S) -> S) {
        _state.update(reducer)
    }

    /** Resets the draft to [value]. Call after save or cancel. */
    fun reset(value: S) {
        _state.value = value
    }
}
