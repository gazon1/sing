@file:Suppress("NoDirectClockSystem")

package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.presentation.viewmodel.CalendarDeps
import com.singularity.todo.feature.calendar.presentation.viewmodel.CalendarViewModel
import com.singularity.todo.feature.reminders.domain.port.ReminderRepository
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * Calendar feature DI: ViewModel and dependencies.
 *
 * Navigation entries live in platform-specific sources (androidMain/jvmMain).
 */
fun calendarModule(): Module = module {
    viewModel { (year: Int, month: Month, mode: CalendarViewMode) ->
        CalendarViewModel(
            deps = CalendarDeps(
                taskRepo = get<TaskRepository>(),
                reminderRepo = get<ReminderRepository>(),
                logger = Logger.withTag("Calendar"),
                today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,
            ),
            initialDate = LocalDate(year, month, 1),
            initialMode = mode,
            crashReporter = get(),
        )
    }
}
