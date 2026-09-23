package com.singularity.todo.feature.calendar_sync.work

/**
 * JVM stub for [CalendarSyncWorkScheduler].
 * Calendar sync is Android-only — this implementation does nothing.
 */
class NoopCalendarSyncWorkScheduler : CalendarSyncWorkScheduler {
    override fun enqueueSync() { /* no-op */ }
    override fun cancelSync() { /* no-op */ }
}
