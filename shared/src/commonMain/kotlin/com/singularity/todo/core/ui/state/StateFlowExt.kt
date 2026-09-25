package com.singularity.todo.core.ui.state

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Atomically updates the state of [MutableStateFlow] using [reducer].
 *
 * Unlike [MutableStateFlow.update][kotlinx.coroutines.flow.MutableStateFlow.update],
 * this extension does not require `@OptIn(kotlin.ExperimentalStdlibApi)`.
 *
 * ## Deprecation (2026-09-26)
 * Use [MviViewModel.updateState] or [StatefulViewModel.setState] instead.
 * This extension is kept for compatibility with ChatViewModel and CalendarViewModel
 * (still migrating to MviViewModel). Will be deleted after all VMs are migrated.
 *
 * Example:
 * ```
 * _state.updateState { it.copy(loading = true) }
 * ```
 */
@Deprecated(
    message = "Use MviViewModel.updateState() or StatefulViewModel.setState() instead. " +
        "This extension will be removed after all ViewModels are migrated to MviViewModel.",
    replaceWith = ReplaceWith(
        "Use the ViewModel's updateState() method instead",
        "com.singularity.todo.core.ui.MviViewModel",
    ),
)
fun <T> MutableStateFlow<T>.updateState(reducer: (T) -> T) {
    value = reducer(value)
}
