package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * [SyncPeriodicTrigger] for platforms with no background job scheduler: a
 * [delay] loop in the caller's scope.
 *
 * Used on the JVM desktop. Lives in commonMain rather than jvmMain because it is
 * plain Kotlin with no platform dependency, and because the loop's two failure modes
 * are worth testing directly.
 *
 * ## The loop outlives a failing cycle
 *
 * The first version of this loop was `while (isActive) { syncOnce(); delay(interval) }`
 * with no guard around the body. One throwing cycle killed the coroutine, and because
 * nothing restarts it, auto-sync was dead for the rest of the process — with no error
 * and no failed job, just a loop that had quietly stopped. A transient network error
 * was enough to cause it.
 *
 * So: the body cannot throw. The cycle's own error handling is inside the cycle; this
 * loop only has to not die.
 */
internal class DelayLoopSyncPeriodicTrigger(
    private val request: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val log: Logger = Logger.withTag("DelayLoopSyncTrigger"),
) : SyncPeriodicTrigger {

    private var job: Job? = null

    override fun start(interval: Duration) {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                runCycle()
                delay(interval)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    @Suppress("TooGenericExceptionCaught") // the point is that a cycle may throw anything; the loop outlives it
    private suspend fun runCycle() {
        try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Swallowed on purpose — see the class doc. The alternative is a loop
            // that never fires again.
            log.e(e) { "Periodic sync cycle failed; the loop continues" }
        }
    }
}
