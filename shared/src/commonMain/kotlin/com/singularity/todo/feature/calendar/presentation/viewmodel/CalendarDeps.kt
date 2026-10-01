@file:Suppress("NoDirectClockSystem")

package com.singularity.todo.feature.calendar.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.reminders.domain.port.ReminderRepository
import com.singularity.todo.feature.tasks.domain.port.TaskRepository

/**
 * Dependencies injected into [CalendarViewModel].
 *
 * @param reminderRepo Provides [ReminderRepository.observeRecurringTaskIds] to enrich
 *   [com.singularity.todo.feature.calendar.domain.model.CalendarTaskUi.isRecurring].
 * @param clock The time source. Defaults to [kotlin.time.Clock.System].
 * @param today Today's date, pre-computed at construction to avoid blocking in the VM.
 *   In production, compute via `kotlin.time.Clock.System.now().toLocalDateTime(zone).date`.
 *   In tests, pass any fixed [LocalDate] for deterministic behavior.
 */
data class CalendarDeps(
    val taskRepo: TaskRepository,
    val reminderRepo: ReminderRepository,
    val clock: kotlin.time.Clock = kotlin.time.Clock.System,
    val today: kotlinx.datetime.LocalDate,
    val logger: Logger,
)
