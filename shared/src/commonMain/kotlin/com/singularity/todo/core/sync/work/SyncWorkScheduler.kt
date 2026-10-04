package com.singularity.todo.core.sync.work

/**
 * Port for scheduling sync jobs via the platform's background job scheduler.
 *
 * Android: delegates to [androidx.work.WorkManager].
 * JVM:     no-op stub — the delay loop in
 *          [com.singularity.todo.core.sync.DelayLoopSyncPeriodicTrigger] drives
 *          periodic sync on desktop instead.
 *
 * Two jobs, not one, because they answer different questions:
 *
 * - [enqueuePush] is *reactive*: the app has a local change and wants it delivered.
 *   Fires once, as soon as the constraints allow.
 * - [enqueuePeriodic] is *polling*: nothing has changed locally, but the server may
 *   have. Fires on an interval, and survives process death.
 */
interface SyncWorkScheduler {

    /**
     * Enqueues a one-shot push job.
     * Uses `ExistingWorkPolicy.KEEP` — subsequent calls while a job is running
     * are idempotent; the running job completes before the new one starts.
     */
    fun enqueuePush()

    /**
     * Cancels any pending push job.
     */
    fun cancelPush()

    /**
     * Enqueues (or re-arms) the periodic sync job at the given [interval].
     *
     * Uses `ExistingPeriodicWorkPolicy.KEEP`, so re-arming with a shorter interval
     * does not stack a second job on top of the first.
     */
    fun enqueuePeriodic(intervalMillis: Long)

    /**
     * Cancels the periodic sync job.
     */
    fun cancelPeriodic()
}
