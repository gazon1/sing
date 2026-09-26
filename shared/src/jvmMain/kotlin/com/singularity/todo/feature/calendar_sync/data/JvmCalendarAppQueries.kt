package com.singularity.todo.feature.calendar_sync.data

import com.singularity.todo.feature.calendar_sync.domain.model.CalendarAppInfo
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarAppQueries

/**
 * JVM stub of [CalendarAppQueries]. Calendar sync is Android-only.
 * Returns an empty list on JVM.
 */
class JvmCalendarAppQueries : CalendarAppQueries {
    override suspend fun listInstalled(): List<CalendarAppInfo> = emptyList()
}
