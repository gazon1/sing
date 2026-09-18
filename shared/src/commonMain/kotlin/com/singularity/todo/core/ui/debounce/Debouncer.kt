package com.singularity.todo.core.ui.debounce

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Debounces a [Flow] and executes [action] with the latest debounced value.
 *
 * The debounce runs within [scope] and is automatically cancelled when [scope]
 * is cancelled (e.g. when the ViewModel is cleared).
 *
 * Example:
 * ```
 * val debouncer = Debouncer(scope, 300.milliseconds)
 *
 * debouncer.debounce(draftState.state.map { it.name }) { name ->
 *     mutate(current) { copy(name = name) }
 * }
 * ```
 *
 * @param scope The [CoroutineScope] to run the debounced collector in.
 *   Pass the injected VM scope (NOT `viewModelScope`).
 * @param duration The debounce duration.
 */
class Debouncer(
    private val scope: CoroutineScope,
    private val duration: Duration,
) {
    /**
     * Debounces [flow] and calls [action] with each debounced value.
     * The collector runs in [scope] and is cancelled when [scope] is cancelled.
     *
     * @return A [Job] that can be cancelled to stop the debounced collector.
     */
    fun <T> debounce(flow: Flow<T>, action: (T) -> Unit): Job =
        scope.launch {
            flow
                .debounce(duration)
                .distinctUntilChanged()
                .collect { value ->
                    action(value)
                }
        }
}

/**
 * Creates a [Debouncer] with a [Long] delay in milliseconds.
 */
fun Debouncer(scope: CoroutineScope, delayMs: Long): Debouncer =
    Debouncer(scope, delayMs.milliseconds)

/**
 * Convenience extension to debounce a [Flow] using a [Debouncer].
 *
 * Example:
 * ```
 * draftState.state.map { it.name }
 *     .debounced(debouncer) { name -> ... }
 * ```
 */
fun <T> Flow<T>.debounced(debouncer: Debouncer, action: (T) -> Unit): Job =
    debouncer.debounce(this, action)
