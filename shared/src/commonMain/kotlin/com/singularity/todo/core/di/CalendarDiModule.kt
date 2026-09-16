package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.presentation.viewmodel.CalendarDeps
import com.singularity.todo.feature.calendar.presentation.viewmodel.CalendarViewModel
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.datetime.LocalDate
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Calendar feature DI: ViewModel and dependencies.
 *
 * Navigation entries live in platform-specific sources (androidMain/jvmMain).
 */
fun calendarModule(): Module = module {
    viewModel { (year: Int, month: Int, mode: CalendarViewMode) ->
        CalendarViewModel(
            deps = CalendarDeps(
                taskRepo = get<TaskRepository>(),
                currentUser = get(),
                logger = Logger.withTag("Calendar"),
            ),
            initialDate = LocalDate(year, month, 1),
            initialMode = mode,
        )
    }
}
