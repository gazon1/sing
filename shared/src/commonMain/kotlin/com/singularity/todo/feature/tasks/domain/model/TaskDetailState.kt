package com.singularity.todo.feature.tasks.domain.model

import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.core.attachments.AttachmentRepository
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.checklist.ChecklistUseCase
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.Project
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.projects.ProjectsRepository
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderRepository
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase
import kotlinx.datetime.LocalDate

/** Combined read model for TaskDetailScreen. */
data class TaskDetailUi(
    val task: Task,
    val project: Project? = null,
    val tags: List<Tag> = emptyList(),
    val checklist: List<ChecklistItem> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    /** Direct child tasks (1-level hierarchy only). */
    val subtasks: List<Task> = emptyList(),
)

sealed interface TaskDetailUiState {
    data object Loading : TaskDetailUiState
    data class Loaded(val ui: TaskDetailUi) : TaskDetailUiState
    data class Error(val message: String) : TaskDetailUiState
}

/**
 * Dependencies for [TaskDetailViewModel] — reduces constructor parameter count
 * and makes DI registration more maintainable.
 */
data class TaskDetailDeps(
    val taskRepo: TaskRepository,
    val updateTask: UpdateTaskUseCase,
    val createTask: CreateTaskUseCase,
    val projectsRepo: ProjectsRepository,
    val tagsRepo: TagsRepository,
    val checklistUseCase: ChecklistUseCase,
    val reminderRepo: ReminderRepository,
    val attachmentsRepo: AttachmentRepository,
    val currentUser: ProfileAwareCurrentUser,
    val timeZoneProvider: TimeZoneProvider,
)

/**
 * Единая точка входа для [TaskDetailViewModel].
 *
 * Routing-варианты (OpenSheet, Navigate*, Attachment.Pick) обрабатываются экраном,
 * доменные — [TaskDetailViewModel.onIntent].
 *
 * Разделение типизировано на уровне sealed-иерархии: попытка передать
 * routing-интент в VM — ошибка компиляции.
 */
sealed interface TaskDetailIntent {

    // ── Routing: owned by screen ────────────────────────────────────────────

    /** Open a bottom sheet or confirmation dialog. */
    data class OpenSheet(val sheet: ActiveSheet) : TaskDetailIntent

    /** Close any open sheet / dialog. */
    data object CloseSheet : TaskDetailIntent

    /** Navigate to a project's detail screen. */
    data class NavigateToProject(val id: ProjectId) : TaskDetailIntent

    /** Navigate to a task's detail screen (parent or subtask). */
    data class NavigateToTask(val id: TaskId) : TaskDetailIntent

    // ── Attachment: owned by screen (delegates to AttachmentsViewModel) ──

    sealed interface Attachment : TaskDetailIntent {
        data class Delete(val attachmentId: com.singularity.todo.core.attachments.AttachmentId) : Attachment
        data class Click(val attachmentId: com.singularity.todo.core.attachments.AttachmentId) : Attachment
        /** Pick a file from the system file picker. */
        data object PickFile : Attachment
    }

    // ── Domain: owned by ViewModel ─────────────────────────────────────────

    sealed interface Domain : TaskDetailIntent {

        // ── Hero ────────────────────────────────────────────────────────────

        data object ToggleComplete : Domain
        data class TitleChanged(val title: String) : Domain
        data class DescriptionChanged(val description: String) : Domain
        data object ToggleSomeday : Domain
        data class SetKind(val kind: TaskKind) : Domain

        // ── Meta fields ─────────────────────────────────────────────────────

        data class SetDueDate(val date: LocalDate?) : Domain
        data class SetDueTime(val time: String?) : Domain
        data class SetPriority(val priority: TaskPriority) : Domain
        data class SetProject(val projectId: ProjectId?) : Domain

        // ── Tags ────────────────────────────────────────────────────────────

        data class SetTags(val tagIds: List<TagId>) : Domain
        data class RemoveTag(val tagId: TagId) : Domain

        // ── Checklist ───────────────────────────────────────────────────────

        data class ToggleChecklistItem(val item: ChecklistItem) : Domain
        data class DeleteChecklistItem(val id: ChecklistItemId) : Domain
        data class AddChecklistItem(val title: String) : Domain

        // ── Subtasks ─────────────────────────────────────────────────────

        data class ToggleSubtask(val task: Task) : Domain
        data class DeleteSubtask(val task: Task) : Domain
        data class AddSubtask(val title: String) : Domain

        // ── Reminders ─────────────────────────────────────────────────────

        data class SetReminder(val offset: ReminderOffset) : Domain
        data object DeleteReminder : Domain

        // ── Lifecycle ─────────────────────────────────────────────────────

        /** Soft-delete + show Undo snackbar. */
        data object Delete : Domain

        /** Soft-delete without Undo (archive). */
        data object Archive : Domain

        /** Restore the last soft-deleted task. */
        data object Restore : Domain

        // ── Pin ─────────────────────────────────────────────────────────────

        data object TogglePinned : Domain
    }
}

// Re-export for convenience
typealias ProjectsRepository = com.singularity.todo.feature.projects.ProjectsRepository
typealias TagsRepository = com.singularity.todo.feature.tags.TagsRepository
typealias ChecklistUseCase = com.singularity.todo.feature.checklist.ChecklistUseCase
typealias ReminderRepository = com.singularity.todo.feature.reminders.ReminderRepository
typealias AttachmentRepository = com.singularity.todo.core.attachments.AttachmentRepository
typealias ProfileAwareCurrentUser = com.singularity.todo.feature.profile.ProfileAwareCurrentUser
typealias TimeZoneProvider = com.singularity.todo.core.platform.TimeZoneProvider
