package com.singularity.todo.feature.tasks.presentation.state

import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Единая точка входа для [com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailViewModel].
 *
 * Все варианты обрабатываются VM через [Domain].
 */
sealed interface TaskDetailIntent {

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
        data class SetDueTime(val time: LocalTime?) : Domain
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

        // ── Dependencies ─────────────────────────────────────────────────────

        data class SetDependencies(val dependsOn: Set<TaskId>) : Domain

        // ── Recurrence ───────────────────────────────────────────────────────

        data class SetRecurrence(val spec: RecurrenceSpec?) : Domain
    }
}
