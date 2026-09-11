package com.singularity.todo.feature.tasks.domain.model

import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.checklist.ChecklistUseCase
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.reminders.ReminderRepository
import com.singularity.todo.feature.tasks.domain.model.AttachmentSaver
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase

/**
 * Dependencies for legacy [com.singularity.todo.feature.tasks.presentation.viewmodel.TaskEditorViewModel].
 * Will be deleted together with TaskEditorViewModel.
 */
data class TaskEditorDeps(
    val createTask: CreateTaskUseCase,
    val updateTask: UpdateTaskUseCase,
    val clock: Clock,
    val currentUser: ProfileAwareCurrentUser,
    val taskRepository: TaskRepository,
    val checklistUseCase: ChecklistUseCase,
    val reminderRepository: ReminderRepository,
    val attachmentSaver: AttachmentSaver,
    val idGen: IdGenerator,
    val timeZoneProvider: TimeZoneProvider,
)
