package com.singularity.todo.feature.agenda.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.domain.usecase.TaskMutationsUseCase

/**
 * Dependencies injected into [AgendaViewModel].
 *
 * @param taskRepo Watches tasks for the current user.
 * @param clock The time source for [kotlin.time.Clock.System.now].
 * @param logger For structured logging.
 * @param draftStore User-scoped draft store for pre-filling task creation from section headers.
 * @param reminderScheduler For cancelling reminders when a task is deleted (undo delete).
 * @param currentUser For obtaining the scoped user id (for reminder cancellation).
 * @param taskMutations Bulk operations (delete, complete) for multi-selection.
 */
data class AgendaDeps(
    val taskRepo: TaskRepository,
    val clock: kotlin.time.Clock,
    // Required beside the clock (#91). `todayAt` no longer defaults its zone, so a
    // ViewModel that resolves a relative due date has to be told which one; taking
    // it from the host here would put back exactly the machine-dependence the
    // parameter was removed to remove.
    val timeZone: com.singularity.todo.core.platform.TimeZoneProvider,
    val logger: Logger,
    val draftStore: DraftStore,
    val reminderScheduler: ReminderScheduler,
    val currentUser: ProfileAwareCurrentUser,
    val taskMutations: TaskMutationsUseCase,
)
