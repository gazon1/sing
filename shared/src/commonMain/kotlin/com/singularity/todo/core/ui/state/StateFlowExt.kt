package com.singularity.todo.core.ui.state

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Atomically updates the state of [MutableStateFlow] using [reducer].
 *
 * Unlike [MutableStateFlow.update][kotlinx.coroutines.flow.MutableStateFlow.update],
 * this extension does not require `@OptIn(kotlin.ExperimentalStdlibApi)`.
 *
 * Example:
 * ```
 * _state.updateState { it.copy(loading = true) }
 * ```
 */
fun <T> MutableStateFlow<T>.updateState(reducer: (T) -> T) {
    value = reducer(value)
}
