package com.singularity.todo.feature.calendar.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.port.TaskRepository

/**
 * Dependencies injected into [CalendarViewModel].
 *
 * Note: [ReminderRepository] is intentionally excluded — [CalendarTaskUi.isRecurring]
 * is `false` for now. A future MR can add a `watchRecurringTaskIds()` call to
 * pre-load recurring reminder IDs and enrich [CalendarTaskUi.isRecurring].
 *
 * @param clock The time source. Defaults to [kotlin.time.Clock.System].
 * @param today Today's date, pre-computed at construction to avoid blocking in the VM.
 *   In production, compute via `kotlin.time.Clock.System.now().toLocalDateTime(zone).date`.
 *   In tests, pass any fixed [LocalDate] for deterministic behavior.
 */
data class CalendarDeps(
    val taskRepo: TaskRepository,
    val currentUser: ProfileAwareCurrentUser,
    val clock: kotlin.time.Clock = kotlin.time.Clock.System,
    val today: kotlinx.datetime.LocalDate,
    val logger: Logger,
)
