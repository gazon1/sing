package com.singularity.todo.feature.calendarSync.data

import com.singularity.todo.feature.calendarSync.domain.model.CalendarAppInfo
import com.singularity.todo.feature.calendarSync.domain.port.CalendarAppQueries

/**
 * JVM stub of [CalendarAppQueries]. Calendar sync is Android-only.
 * Returns an empty list on JVM.
 */
class JvmCalendarAppQueries : CalendarAppQueries {

    /** The desktop has no system calendar this app may write to. */
    override val isSupported: Boolean = false
    override suspend fun listInstalled(): List<CalendarAppInfo> = emptyList()
}
