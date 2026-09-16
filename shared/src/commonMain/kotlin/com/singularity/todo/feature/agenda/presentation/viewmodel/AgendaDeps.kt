package com.singularity.todo.feature.agenda.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.port.TaskRepository

/**
 * Dependencies injected into [AgendaViewModel].
 *
 * Mirrors the [com.singularity.todo.feature.calendar.presentation.viewmodel.CalendarDeps] pattern.
 *
 * @param taskRepo Watches tasks for the current user.
 * @param currentUser Provides reactive user ID scoped to the current profile.
 * @param logger For structured logging.
 */
data class AgendaDeps(val taskRepo: TaskRepository, val currentUser: ProfileAwareCurrentUser, val logger: Logger)
