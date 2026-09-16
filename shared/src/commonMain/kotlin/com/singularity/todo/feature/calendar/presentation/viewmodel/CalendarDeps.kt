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
 */
data class CalendarDeps(
    val taskRepo: TaskRepository,
    val currentUser: ProfileAwareCurrentUser,
    val logger: Logger,
)
