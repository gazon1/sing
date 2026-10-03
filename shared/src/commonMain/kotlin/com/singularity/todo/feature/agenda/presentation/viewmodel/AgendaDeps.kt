package com.singularity.todo.feature.agenda.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.tasks.domain.port.TaskRepository

/**
 * Dependencies injected into [AgendaViewModel].
 *
 * @param taskRepo Watches tasks for the current user.
 * @param clock The time source for [kotlin.time.Clock.System.now].
 * @param logger For structured logging.
 * @param draftStore User-scoped draft store for pre-filling task creation from section headers.
 * @param reminderScheduler For cancelling reminders when a task is deleted (undo delete).
 * @param currentUser For obtaining the scoped user id (for reminder cancellation).
 */
data class AgendaDeps(
    val taskRepo: TaskRepository,
    val clock: kotlin.time.Clock,
    val logger: Logger,
    val draftStore: DraftStore,
    val reminderScheduler: ReminderScheduler,
    val currentUser: ProfileAwareCurrentUser,
)
