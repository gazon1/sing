package com.singularity.todo.feature.calendar_sync.work

/**
 * Port for scheduling calendar sync work on the platform's background job scheduler.
 *
 * Android: delegates to WorkManager via [AndroidCalendarSyncWorkScheduler].
 * JVM:     no-op stub — calendar sync is Android-only.
 */
interface CalendarSyncWorkScheduler {

    /**
     * Enqueues a one-shot calendar sync job.
     * Uses `ExistingWorkPolicy.KEEP` — calling this while a job is already running
     * is idempotent; the running job completes naturally.
     */
    fun enqueueSync()

    /**
     * Cancels any pending sync job.
     */
    fun cancelSync()
}
