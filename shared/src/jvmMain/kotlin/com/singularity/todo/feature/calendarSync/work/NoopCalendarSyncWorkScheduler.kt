package com.singularity.todo.feature.calendarSync.work

/**
 * JVM stub for [CalendarSyncWorkScheduler].
 * Calendar sync is Android-only — this implementation does nothing.
 */
class NoopCalendarSyncWorkScheduler : CalendarSyncWorkScheduler {

    /** The desktop has no system calendar this app may write to. */
    override val isSupported: Boolean = false
    override fun enqueueSync() { /* no-op */ }
    override fun cancelSync() { /* no-op */ }
}
