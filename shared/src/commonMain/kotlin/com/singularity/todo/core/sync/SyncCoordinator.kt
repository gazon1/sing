package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.toAppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Single owner of the sync cycle.
 *
 * Every trigger — the periodic driver, the pull-to-refresh gesture, the
 * WorkManager job, the alarm broadcast — funnels through [request]. A conflated
 * channel collapses any number of them into one pending cycle, and exactly one
 * consumer reads it, so two cycles can never overlap.
 *
 * ## Why not a status check
 *
 * The previous implementation guarded re-entry with `engine.status.value.isRunning()`.
 * That is not a re-entry guard, it is a status read, and the engine resets the status
 * between its push phase and its pull phase: `push()` sets it to `Idle` on the way out
 * and `pull()` sets it to `Pulling` on the way in. A caller arriving in that window saw
 * `Idle` and started a second cycle. Two cycles meant two pulls racing on the same
 * download cursor and two pushes of the same outbox rows.
 *
 * The window is the whole point: the check reads a value the running cycle itself
 * mutates, so the check is only as good as the moment it happens to land. A single
 * consumer has no such moment — it cannot be in the loop twice, and it does not care
 * what status anyone else is reading.
 *
 * A lock would also have fixed the overlap, but it buys mutual exclusion and not
 * coalescing, and building coalescing on top of one is how the previous two-mechanism
 * design produced a bug. One mechanism here.
 *
 * ## Outcome contract
 *
 * [request] suspends until a cycle that started after it was requested has completed,
 * and returns *that* cycle's outcome. So callers no longer get `Skipped` for a request
 * that was absorbed into a running cycle — they get the result of the work they asked
 * for. `Skipped` is now returned only when the coordinator is closed.
 */
internal class SyncCoordinator(
    private val runCycle: suspend () -> SyncOutcome,
    private val scope: AutoCloseableCoroutineScope,
    private val log: Logger = Logger.withTag("SyncCoordinator"),
) {
    /**
     * Conflated: `trySend` on a channel that already holds a pending signal replaces
     * it, so N requests during a cycle leave exactly one follow-up.
     */
    private val triggers = Channel<Unit>(Channel.CONFLATED)

    /**
     * Count of completed cycles. A requester captures the current value, then waits
     * for it to advance — which happens once per cycle the consumer runs, including
     * the follow-up that absorbed its own request.
     */
    private val completedCycles = MutableStateFlow(0L)

    private val lastOutcome = MutableStateFlow<SyncOutcome?>(null)

    init {
        scope.launch {
            for (ignored in triggers) {
                val outcome = runCycleCatching()
                lastOutcome.value = outcome
                completedCycles.value += 1
            }
        }
    }

    /**
     * Requests a sync cycle and suspends until a cycle started after this call has
     * completed.
     *
     * @return that cycle's outcome, or [SyncOutcome.Skipped] if the coordinator was
     *   closed while waiting.
     */
    suspend fun request(): SyncOutcome {
        val seen = completedCycles.value
        if (!triggers.trySend(Unit).isSuccess) {
            return SyncOutcome.Skipped("Sync coordinator is closed")
        }
        return awaitCycleAfter(seen)
    }

    /**
     * Closes the trigger channel. In-flight waiters are released with `Skipped`
     * rather than left suspended forever.
     */
    fun close() {
        triggers.close()
        // Release anyone waiting on a cycle that will now never come. `first` sees
        // the new value and returns; the id being MAX_VALUE cannot be reached by
        // the counter, so this is unambiguous.
        completedCycles.value = Long.MAX_VALUE
    }

    private suspend fun awaitCycleAfter(seen: Long): SyncOutcome {
        val completed = completedCycles.first { it > seen }
        if (completed == Long.MAX_VALUE) {
            return SyncOutcome.Skipped("Sync coordinator is closed")
        }
        return lastOutcome.value ?: SyncOutcome.Skipped("Sync cycle produced no outcome")
    }

    /**
     * Runs one cycle, converting an escaping exception into a failed outcome.
     *
     * A throw here would kill the consumer coroutine and every later request would
     * wait for a cycle that never arrives — a silent permanent stop of all sync,
     * which is what happens to the delay loop when a cycle throws.
     *
     * The outcome is [SyncOutcome.Failed], not a [SyncOutcome.Success] with the same
     * error in both phases. The exception came from somewhere inside the cycle and
     * this function cannot know where, so the only honest claim is that the cycle did
     * not complete; claiming a push and a pull both failed asserted that work had run
     * and failed when it may never have started.
     */
    @Suppress("TooGenericExceptionCaught") // a cycle may throw anything; the consumer must outlive it
    private suspend fun runCycleCatching(): SyncOutcome = try {
        runCycle()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.e(e) { "Sync cycle threw; continuing" }
        SyncOutcome.Failed(e.toAppError())
    }
}
