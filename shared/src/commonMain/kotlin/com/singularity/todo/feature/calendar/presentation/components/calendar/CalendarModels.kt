package com.singularity.todo.feature.calendar.presentation.components.calendar

import com.singularity.todo.feature.calendar.domain.model.CalendarTaskUi
import kotlinx.datetime.LocalDate

/**
 * Type alias so that mock-derived component files can import [CalendarTaskUi]
 * under the original name `CalendarTask` without changing their signatures.
 */
typealias CalendarTask = CalendarTaskUi

/** Groups tasks by their date for efficient grid rendering. */
fun List<CalendarTask>.groupedByDate(): Map<LocalDate, List<CalendarTask>> = groupBy { it.date }
