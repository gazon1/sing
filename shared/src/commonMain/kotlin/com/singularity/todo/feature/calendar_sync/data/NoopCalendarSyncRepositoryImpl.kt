package com.singularity.todo.feature.calendar_sync.data

import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncStatus
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarSyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Inert [CalendarSyncRepository] for platforms with no calendar provider.
 *
 * Compiled into every target from `commonMain`, but bound only on desktop —
 * `PlatformModule.android.kt` binds [CalendarSyncSettingsRepositoryImpl], which
 * does the real work. So this is not a JVM stub: the Android binary contains the
 * class and never binds it.
 */
class NoopCalendarSyncRepositoryImpl : CalendarSyncRepository {
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
