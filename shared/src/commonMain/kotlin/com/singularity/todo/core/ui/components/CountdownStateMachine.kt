package com.singularity.todo.core.ui.components

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives an undo-snackbar countdown using **tick counting** — not wall-clock time —
 * making it fully compatible with Kotlinx Coroutines virtual time in tests.
 *
 * ## Usage
 *
 * ```
 * private val countdown = CountdownStateMachine(
 *     scope = scope,
 *     windowMs = 5_000L,
 *     onExpired = { _pendingDelete.value = null },
 * )
 * val countdownProgress: StateFlow<Float?> = countdown.progress
 *
 * // Start when delete is requested (predicate = current generation identity):
 * countdown.start { undoSlot.load() == generation }
 *
 * // Cancel when undo succeeds:
 * countdown.cancel()
 * ```
 *
 * The `start` predicate is re-evaluated on every tick. When it returns `false`
 * (because the generation advanced), the countdown is silently cancelled and the
 * slot is freed for the next delete operation without calling [onExpired].
 *
 * @param scope The coroutine scope to run the countdown job on. Must be
 *   `AutoCloseableCoroutineScope` so the machine can cancel the job when closed.
 * @param windowMs Total countdown duration in milliseconds. Must be positive.
 * @param onExpired Called when the countdown completes without being cancelled.
 *   The caller is responsible for clearing any associated state (e.g. `_pendingDelete`).
 */
class CountdownStateMachine(
    private val scope: AutoCloseableCoroutineScope,
    private val windowMs: Long,
    private val onExpired: () -> Unit,
) {
    companion object {
        /** Tick interval. Kept small so progress updates feel smooth. */
        private const val TICK_MS = 100L
    }

    private val _progress = MutableStateFlow<Float?>(null)
    /** `null` when idle; `0f..1f` during countdown (1f = just started, 0f = expired). */
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    private var job: Job? = null

    /**
     * Starts a new countdown, cancelling any previously running one first.
     *
     * @param currentGeneration A predicate that returns `true` while this countdown
     *   is still the current operation. When it returns `false` (generation changed),
     *   the countdown is cancelled silently. Pass a closure over the VM's generation
     *   slot to avoid allocating an `AtomicInt` per instance.
     */
    fun start(currentGeneration: () -> Boolean) {
        job?.cancel()
        job = scope.launch {
            if (!currentGeneration()) return@launch

            // Total ticks needed to fill the window. Using tick counting (not wall-clock
            // time) ensures this is fully virtual-time compatible: advanceTimeBy() in
            // tests advances the delay() clock, so the loop exits after the correct
            // virtual tick count regardless of real elapsed time.
            val totalTicks = (windowMs + TICK_MS - 1) / TICK_MS
            var ticksElapsed = 0

            while (true) {
                delay(TICK_MS)
                ticksElapsed++

                if (!currentGeneration()) break

                if (ticksElapsed >= totalTicks) {
                    _progress.value = null
                    onExpired()
                    break
                }

                // progress: 1f at start, 0f at expiry
                val remaining = totalTicks - ticksElapsed
                _progress.value = remaining.toFloat() / totalTicks
            }
        }
    }

    /** Cancels any running countdown without calling [onExpired]. */
    fun cancel() {
        job?.cancel()
        job = null
        _progress.value = null
    }
}
