package com.singularity.todo.feature.calendarSync.work

/**
 * Port for scheduling calendar sync work on the platform's background job scheduler.
 *
 * Android: delegates to WorkManager via [AndroidCalendarSyncWorkScheduler].
 * JVM:     no-op stub — calendar sync is Android-only.
 */
interface CalendarSyncWorkScheduler {

    /**
     * Whether this port can do its job on this platform.
     *
     * False means every call below is answered with "not available here", and the
     * screen has to say so instead of offering a control that does nothing. The
     * same reason [com.singularity.todo.feature.pomodoro.PomodoroTaskListProvider.isSupported]
     * exists: an empty list is an artifact of the platform, not a fact about the
     * user's calendar.
     */
    val isSupported: Boolean get() = true

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
