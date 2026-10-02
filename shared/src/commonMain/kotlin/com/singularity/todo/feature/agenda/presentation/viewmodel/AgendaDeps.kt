package com.singularity.todo.feature.agenda.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.tasks.domain.port.TaskRepository

/**
 * Dependencies injected into [AgendaViewModel].
 *
 * @param taskRepo Watches tasks for the current user.
 * @param clock The time source for [kotlin.time.Clock.System.now].
 * @param logger For structured logging.
 */
data class AgendaDeps(val taskRepo: TaskRepository, val clock: kotlin.time.Clock, val logger: Logger)
