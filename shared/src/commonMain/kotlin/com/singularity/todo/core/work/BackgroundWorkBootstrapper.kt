package com.singularity.todo.core.work

/**
 * Arms the maintenance jobs that have no user-facing trigger, once per app launch.
 *
 * ## Why this exists rather than each entry point scheduling directly
 *
 * Android (`SingularityApp.onCreate`) and Desktop (`main.kt`) each already reach into
 * the graph at launch to start something — a periodic trigger, the profile bootstrapper.
 * Scheduling maintenance work from both would put the same policy in two entry points,
 * and the two would drift: one gets a retention window, the other does not, and the
 * divergence is invisible until the table grows differently per platform.
 *
 * This follows the `ProfileBootstrapper` precedent instead — one class in common code,
 * one call from each entry point — so the *policy* (which jobs run, on what schedule)
 * is written once and both platforms inherit it.
 *
 * ## Why only the prune job is armed
 *
 * See [PruneLlmUsageJob] for why auto-backup and archive/purge are absent: neither has
 * the configuration a job needs, and inventing one inside a background worker is how an
 * inert job gets shipped. Adding a job here is deliberately a two-line change — the job
 * and one `arm` call — so that adding a job without a policy is visible as a missing
 * arm, rather than as a job that quietly does nothing.
 *
 * ## Idempotency
 *
 * [BackgroundWorkScheduler.schedule] is idempotent while a run is in flight
 * (`ExistingWorkPolicy.KEEP`), so calling this on every launch re-arms rather than
 * stacking. Safe to call unconditionally.
 */
class BackgroundWorkBootstrapper(private val scheduler: BackgroundWorkScheduler) {

    /**
     * Arm every maintenance job. Safe to call on every app start.
     *
     * Called from a coroutine scope owned by the entry point, because
     * [BackgroundWorkScheduler.schedule] is not a suspending function but the jobs it
     * arms may start immediately.
     */
    suspend fun run() {
        // Daily at 04:20 rather than 03:00: a wall-clock minute with no competition in
        // it, so a desktop that suspends overnight and a phone that is on Doze both
        // reach the job rather than colliding with the sync poll that sits near 03:00.
        scheduler.schedule(PruneLlmUsageJob.ID, JobSchedule.Daily(atHour = 4, atMinute = 20))
    }
}
