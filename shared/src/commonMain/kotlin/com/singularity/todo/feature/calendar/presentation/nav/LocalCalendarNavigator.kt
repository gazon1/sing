package com.singularity.todo.feature.calendar.presentation.nav

import androidx.compose.runtime.compositionLocalOf

/**
 * Provides [CalendarNavigator] to all Calendar feature screens.
 * Must be provided by [CalendarNavGraph].
 */
val LocalCalendarNavigator = compositionLocalOf<CalendarNavigator> {
    error("CalendarNavigator not provided — wrap with CalendarNavGraph")
}
