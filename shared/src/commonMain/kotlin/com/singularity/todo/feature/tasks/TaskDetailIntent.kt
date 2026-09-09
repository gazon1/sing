package com.singularity.todo.feature.tasks

import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.settings.ReminderOffset
import com.singularity.todo.feature.tags.TagId
import kotlinx.datetime.LocalDate

/**
 * Единая точка входа для [TaskDetailViewModel].
 *
 * Routing-варианты (OpenSheet, Navigate*, Attachment.Pick) обрабатываются экраном,
 * доменные — [TaskDetailViewModel.onIntent].
 *
 * Разделение типизировано на уровне sealed-иерархии: попытка передать
 * routing-интент в VM — ошибка компиляции.
 *
 * TaskDetail — *write-through* редактор: [TaskDetailUi] полностью выводится из
 * Room-флоу, локального состояния для редукции нет. Поэтому редьюсер не нужен;
 * сравни с [TaskEditorViewModel], где локальный черновик редактируется через
 * [com.singularity.todo.feature.tasks.TaskEditorUiState.reduce].
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

    // ── Attachment: owned by screen (delegates to AttachmentsViewModel) ──────

    sealed interface Attachment : TaskDetailIntent {
        data class Delete(val attachmentId: com.singularity.todo.core.attachments.AttachmentId) : Attachment
        data class Click(val attachment: com.singularity.todo.core.attachments.Attachment) : Attachment
        /** Pick a file from the system file picker. */
        data object PickFile : Attachment
    }

    // ── Domain: owned by ViewModel ──────────────────────────────────────────

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

        // ── Subtasks ───────────────────────────────────────────────────────

        data class ToggleSubtask(val task: Task) : Domain
        data class DeleteSubtask(val task: Task) : Domain
        data class AddSubtask(val title: String) : Domain

        // ── Reminders ───────────────────────────────────────────────────────

        data class SetReminder(val offset: ReminderOffset) : Domain
        data object DeleteReminder : Domain

        // ── Lifecycle ───────────────────────────────────────────────────────

        /** Soft-delete + show Undo snackbar. */
        data object Delete : Domain

        /** Soft-delete without Undo (archive). */
        data object Archive : Domain

        /** Restore the last soft-deleted task. */
        data object Restore : Domain

        // ── Pin ────────────────────────────────────────────────────────────

        data object TogglePinned : Domain
    }
}
