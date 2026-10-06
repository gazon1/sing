package com.singularity.todo.core.work

import kotlin.time.Duration

/**
 * Runs named work in the background, on whichever background facility the platform has.
 *
 * ## Why one port instead of a third WorkManager wrapper
 *
 * The repository already had two of these — `SyncWorkScheduler` and
 * `CalendarSyncWorkScheduler` — each with an Android WorkManager implementation and a JVM
 * no-op. Both JVM implementations did nothing at all, which is why
 * `scripts/find-unwired-surfaces.py` could not help: those ports are bound, injected and
 * called; they are simply inert. The seam registry records them as
 * `platform-seams.tsv` rows with that reason written down.
 *
 * A third wrapper would have been easier than unifying two, and would have left the same
 * hole one level over.
 *
 * ## Platform behaviour is deliberately not symmetric
 *
 * Android runs work through WorkManager and it survives process death. The JVM
 * implementation runs it in-process, so **work does not survive the app exiting** and a job
 * missed while the app was closed is not replayed. Jobs that must not be missed have to
 * re-arm themselves on start; `RescheduleRemindersJob` is the pattern.
 *
 * Callers should not depend on the difference. Schedule by intent and let each platform do
 * what it can.
 */
interface BackgroundWorkScheduler {

    /**
     * Ensure [jobId] runs on [schedule].
     *
     * Idempotent while a run is in flight, mirroring `ExistingWorkPolicy.KEEP`: a second
     * call while the job is already running is satisfied by that run rather than stacking a
     * second one. Re-scheduling after it completes re-arms normally.
     *
     * @throws IllegalArgumentException if [jobId] is not in the job catalogue.
     */
    fun schedule(jobId: String, schedule: JobSchedule)

    /** Cancel pending and running work for [jobId]. A no-op if it is not scheduled. */
    fun cancel(jobId: String)

    /**
     * Run [jobId] now, once, without touching its schedule.
     *
     * The reactive counterpart to [schedule]: "something changed locally, deliver it now".
     * A caller may [schedule] a job and still `runNow` it — that is how sync push works,
     * with a periodic schedule for polling and an immediate run for local changes.
     *
     * @throws IllegalArgumentException if [jobId] is not in the job catalogue.
     */
    fun runNow(jobId: String)
}

/**
 * How a job run ended.
 *
 * [Skipped] is not an error state — "auto backup is disabled" is a successful outcome of
 * asking whether to back up. It exists so that "nothing happened" and "nothing happened
 * *on purpose*" are distinguishable in the logs.
 *
 * Every scheduler logs the outcome it receives, which is what keeps this type from
 * becoming a sealed hierarchy with no reader.
 */
sealed interface JobOutcome {
    data object Success : JobOutcome
    data class Skipped(val reason: String) : JobOutcome
    data class Failed(val error: Throwable) : JobOutcome
}

/**
 * A named unit of background work.
 *
 * Implementations must be safe to run more than once: [BackgroundWorkScheduler] gives no
 * exactly-once guarantee on either platform, and a job that re-armed itself on failure will
 * be retried.
 */
interface BackgroundJob {
    /** Stable identifier. Must match the id used in the catalogue and in any schedule. */
    val id: String

    /** The work itself. Throwing is equivalent to returning [JobOutcome.Failed]. */
    suspend fun run(): JobOutcome
}

/**
 * The set of jobs the schedulers may run.
 *
 * Both platforms resolve a [BackgroundWorkScheduler] call against this catalogue, which is
 * why Android needs no map of id to Worker class in common code: `BackgroundJobWorker`
 * takes the id from its input data and looks it up here.
 *
 * A job that is not in the catalogue cannot be scheduled — [BackgroundWorkScheduler]
 * throws rather than silently ignoring it, because "enqueued nothing" is exactly the
 * failure this whole package exists to stop.
 */
interface BackgroundJobCatalog {
    fun find(jobId: String): BackgroundJob?
    fun all(): List<BackgroundJob>
}

/** Wall-clock milliseconds handed to WorkManager, which counts in milliseconds. */
internal fun Duration.toWorkMillis(): Long = inWholeMilliseconds
