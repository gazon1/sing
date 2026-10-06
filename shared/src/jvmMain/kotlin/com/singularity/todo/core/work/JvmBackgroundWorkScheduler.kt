package com.singularity.todo.core.work

import co.touchlab.kermit.Logger
import com.singularity.todo.core.observability.CrashReportingPort
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The JVM implementation of [BackgroundWorkScheduler], and the first real background
 * executor this repository has had on Desktop.
 *
 * ## What replaces
 *
 * `NoopSyncWorkScheduler` and `NoopCalendarSyncWorkScheduler`, both of which resolved and
 * did nothing. `SyncEngine` calls `enqueuePush()` on every sign-in; on Desktop that call
 * returned void and the patches stayed queued. The tests did not catch it because they
 * assert against `FakeSyncWorkScheduler` — they prove the call happens, never that anything
 * results.
 *
 * ## Honest limits, so nobody is surprised
 *
 * This is in-process work. **It does not survive the app exiting.** A daily job whose 03:00
 * window passed while the app was closed runs at the next start, not at 03:00. That is a
 * different guarantee from Android's, and callers that need a missed job replayed must
 * re-arm on start.
 *
 * The registry holds at most one coroutine per job id, so its size is bounded by the
 * catalogue rather than by how often anything is scheduled.
 */
class JvmBackgroundWorkScheduler(
    private val catalog: BackgroundJobCatalog,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val logger: Logger = Logger.withTag("JvmBackgroundWork"),
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val crashReporter: CrashReportingPort,
) : BackgroundWorkScheduler {

    /**
     * Scheduled loops, by job id.
     *
     * Guarded by [lock] because [schedule] and [runNow] are callable from any thread and a
     * plain `MutableMap` is not a concurrency story anyone should have to reason about.
     */
    private val scheduled = mutableMapOf<String, Job>()

    /** Ids with a run in flight, so a duplicate [runNow] does not double-fire. */
    private val inFlight = mutableSetOf<String>()

    private val lock = Any()

    override fun schedule(jobId: String, schedule: JobSchedule) {
        val job = requireJob(jobId)
        synchronized(lock) {
            // KEEP: an existing loop is still this job's schedule. Re-scheduling an armed
            // job would restart its clock, which for a daily job means moving its 03:00.
            if (scheduled[jobId]?.isActive == true) {
                logger.d { "$jobId already scheduled; keeping the existing loop" }
                return
            }
            scheduled[jobId] = scope.launch(CoroutineName("bg-$jobId")) {
                loop(jobId, job, schedule)
            }
        }
    }

    override fun cancel(jobId: String) {
        synchronized(lock) {
            scheduled.remove(jobId)?.cancel()
        }
    }

    override fun runNow(jobId: String) {
        val job = requireJob(jobId)
        synchronized(lock) {
            if (!inFlight.add(jobId)) {
                logger.d { "$jobId already running; this run is satisfied by it" }
                return
            }
        }
        scope.launch(CoroutineName("bg-now-$jobId")) {
            try {
                runGuarded(job)
            } finally {
                synchronized(lock) { inFlight.remove(jobId) }
            }
        }
    }

    private fun requireJob(jobId: String): BackgroundJob =
        catalog.find(jobId)
            ?: throw IllegalArgumentException(
                "No background job with id '$jobId'. A scheduler call that resolves to " +
                    "nothing is the exact failure this package exists to prevent — register " +
                    "the job in the catalogue or use an id that exists.",
            )

    private suspend fun loop(jobId: String, job: BackgroundJob, schedule: JobSchedule) {
        when (schedule) {
            JobSchedule.OnDemand -> runGuarded(job)

            is JobSchedule.Periodic -> {
                val interval = schedule.interval
                while (currentScopeActive()) {
                    delay(interval)
                    runGuarded(job)
                }
            }

            // OnDemand and Periodic are handled above; these two have a wall-clock target
            // and re-derive it after every run, so a machine that slept for a day wakes
            // up and lands on the next boundary instead of firing a burst of catch-ups.
            is JobSchedule.Daily, is JobSchedule.Weekly -> {
                while (currentScopeActive()) {
                    val wait = JobScheduleMath.delayUntil(schedule, nowLocal(), zone)
                    if (wait > kotlin.time.Duration.ZERO) {
                        delay(wait)
                    }
                    runGuarded(job)
                }
            }
        }
        logger.d { "$jobId loop finished" }
    }

    /**
     * Runs one job, converting every outcome into a logged line.
     *
     * A throw here must not reach the loop: the loop would die and the job would be dead
     * for the rest of the session with no trace anywhere. That is not hypothetical — an
     * unguarded body in `DelayLoopSyncPeriodicTrigger` is why desktop auto-sync stopped
     * working until someone read a log file.
     */
    @Suppress("TooGenericExceptionCaught") // see KDoc: the loop must outlive any throw
    private suspend fun runGuarded(job: BackgroundJob) {
        val outcome = try {
            job.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "${job.id} failed" }
            crashReporter.report(e, "background_job.failed")
            JobOutcome.Failed(e)
        }
        when (outcome) {
            is JobOutcome.Success -> logger.d { "${job.id}: success" }

            is JobOutcome.Skipped -> logger.d { "${job.id}: skipped (${outcome.reason})" }

            is JobOutcome.Failed -> {
                logger.e(outcome.error) { "${job.id}: failed (${outcome.error.message})" }
                crashReporter.report(outcome.error, "background_job.failed")
            }
        }
    }

    private fun nowLocal() = clock.now().toLocalDateTime(zone)

    private fun currentScopeActive(): Boolean = scope.isActive
}
