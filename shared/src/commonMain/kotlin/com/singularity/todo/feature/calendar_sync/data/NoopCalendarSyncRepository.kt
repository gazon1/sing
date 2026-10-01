package com.singularity.todo.feature.calendar_sync.data

import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncStatus
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarSyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * JVM stub [CalendarSyncRepository].
 * Calendar sync is Android-only; this implementation does nothing.
 */
class NoopCalendarSyncRepository : CalendarSyncRepository {
    override fun observeEnabled(): Flow<Boolean> = flowOf(false)
    override suspend fun setEnabled(enabled: Boolean) {}
    override fun observeTargetCalendarId(): Flow<String?> = flowOf(null)
    override suspend fun setTargetCalendarId(calendarId: String) {}
    override fun observeLastSyncedAt(): Flow<Long?> = flowOf(null)
    override suspend fun setLastSyncedAt(ts: Long) {}
    override fun observeStatus(): Flow<CalendarSyncStatus> = flowOf(CalendarSyncStatus.Disabled)
    override suspend fun setStatus(status: CalendarSyncStatus) {}
    override fun observeTargetAppPackage(): Flow<String?> = flowOf(null)
    override suspend fun setTargetAppPackage(packageName: String?) {}
}
