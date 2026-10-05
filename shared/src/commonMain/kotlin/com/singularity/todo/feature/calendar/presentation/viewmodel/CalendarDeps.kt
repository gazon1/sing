package com.singularity.todo.feature.calendar.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.reminders.domain.port.ReminderRepository
import com.singularity.todo.feature.tasks.domain.port.TaskRepository

/**
 * Dependencies injected into [CalendarViewModel].
 *
 * @param reminderRepo Provides [ReminderRepository.observeRecurringTaskIds] to enrich
 *   [com.singularity.todo.feature.calendar.domain.model.CalendarTaskUi.isRecurring].
 * @param today Today's date, pre-computed at construction to avoid blocking in the VM.
 *   In production, compute via `kotlin.time.Clock.System.now().toLocalDateTime(zone).date`.
 *   In tests, pass any fixed [LocalDate] for deterministic behavior.
 *
 * `clock` was removed 2026-10-05. It defaulted to `Clock.System` and was read
 * nowhere in `feature/calendar/`; the only construction site
 * (`CalendarDiModule.kt`) never passed it. The date the calendar actually runs on
 * is [today], and the fact that the DI module computes *that* from the system
 * clock is the real defect — tracked in #91, not fixed here, because it changes
 * what the module wires rather than what this class declares.
 *
 * The `@file:Suppress("NoDirectClockSystem")` that used to sit on line 1 went
 * with it. It had no reason attached, and the only `Clock.System` it covered
 * here was that dead default — a suppression over the one line that had been
 * written correctly.
 */
data class CalendarDeps(
    val taskRepo: TaskRepository,
    val reminderRepo: ReminderRepository,
    val today: kotlinx.datetime.LocalDate,
    val logger: Logger,
)
