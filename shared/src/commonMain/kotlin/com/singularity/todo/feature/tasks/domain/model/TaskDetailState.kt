package com.singularity.todo.feature.tasks.domain.model

import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.checklist.ChecklistRepository
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.reminders.ReminderRepository
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.domain.usecase.CompleteRecurringTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase

/**
 * Dependencies for [com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailViewModel].
 */
data class TaskDetailDeps(
    val taskRepo: TaskRepository,
    val updateTask: UpdateTaskUseCase,
    val createTask: CreateTaskUseCase,
    val projectsRepo: ProjectsRepository,
    val tagsRepo: com.singularity.todo.feature.tags.TagsRepository,
    val checklistRepository: ChecklistRepository,
    val reminderRepo: ReminderRepository,
    val reminderScheduler: ReminderScheduler,
    val attachmentsRepo: AttachmentRepository,
    val timeZoneProvider: TimeZoneProvider,
    val clock: Clock,
    val completeRecurring: CompleteRecurringTaskUseCase,
    /** Debounce duration for title/description edits. Exposed for tests to use short durations. */
    val debounceMs: Long = 300L,
)
