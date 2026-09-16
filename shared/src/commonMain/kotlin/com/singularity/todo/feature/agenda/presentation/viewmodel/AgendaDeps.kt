package com.singularity.todo.feature.agenda.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.port.TaskRepository

/**
 * Dependencies injected into [AgendaViewModel].
 *
 * @param taskRepo Watches tasks for the current user.
 * @param currentUser Provides reactive user ID scoped to the current profile.
 * @param clock The time source for [kotlin.time.Clock.System.now]. Defaults to [kotlin.time.Clock.System].
 * @param logger For structured logging.
 */
data class AgendaDeps(
    val taskRepo: TaskRepository,
    val currentUser: ProfileAwareCurrentUser,
    val clock: kotlin.time.Clock = kotlin.time.Clock.System,
    val logger: Logger,
)
