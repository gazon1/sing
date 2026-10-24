package com.singularity.todo.feature.calendarSync.data

import com.singularity.todo.feature.calendarSync.domain.model.CalendarAppInfo
import com.singularity.todo.feature.calendarSync.domain.port.CalendarAppQueries

/**
 * JVM stub of [CalendarAppQueries]. Calendar sync is Android-only.
 * Returns an empty list on JVM.
 */
class JvmCalendarAppQueries : CalendarAppQueries {
    override suspend fun listInstalled(): List<CalendarAppInfo> = emptyList()
}
