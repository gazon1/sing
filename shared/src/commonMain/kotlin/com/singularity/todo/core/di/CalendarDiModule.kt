package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.platform.todayAt
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.presentation.viewmodel.CalendarDeps
import com.singularity.todo.feature.calendar.presentation.viewmodel.CalendarViewModel
import com.singularity.todo.feature.reminders.domain.port.ReminderRepository
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * Calendar feature DI: ViewModel and dependencies.
 *
 * Navigation entries live in platform-specific sources (androidMain/jvmMain).
 *
 * The `today` the ViewModel runs on is computed here, at graph-construction time,
 * and used to be read from the system clock directly on line 32. That was the
 * defect behind #91: a desktop flow test could not pin a date, because the value
 * was fixed before the test could reach it, and the attempt was reverted.
 *
 * Both time sources are now resolved from the graph — `Clock` and
 * `TimeZoneProvider` are registered in `CoreDiModule` — so a test that overrides
 * either one in its Koin module changes the date the calendar hangs on. The
 * module no longer reads a clock, which is also why the `@file:Suppress` that
 * used to sit on line 1 could be deleted rather than justified: this is a module
 * that *wires* a default, and wiring a default is what modules are for.
 */
fun calendarModule(): Module = module {
    viewModel { (year: Int, month: Month, mode: CalendarViewMode) ->
        CalendarViewModel(
            deps = CalendarDeps(
                taskRepo = get<TaskRepository>(),
                reminderRepo = get<ReminderRepository>(),
                logger = Logger.withTag("Calendar"),
                today = todayAt(get<Clock>(), get<TimeZoneProvider>().current()),
            ),
            initialDate = LocalDate(year, month, 1),
            initialMode = mode,
            crashReporter = get(),
        )
    }
}
