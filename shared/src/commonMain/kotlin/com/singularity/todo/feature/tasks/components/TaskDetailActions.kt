package com.singularity.todo.feature.tasks.components

import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.Task
import com.singularity.todo.feature.tasks.TaskId

/**
 * All callback actions available in the [com.singularity.todo.feature.tasks.TaskDetailScreen].
 * Packed into a single [JvmInline value class][value class] so every section composable
 * receives exactly one `actions` parameter instead of 10–20 individual lambdas.
 *
 * 27 individual callbacks grouped as:
 * - **Hero**: toggle, title, description
 * - **Meta chips**: date / time / priority / project pickers, navigate-to-project
 * - **Tags**: add / remove
 * - **Checklist**: toggle / delete / add items
 * - **Bottom bar**: remind, attach, pin, delete
 */
@JvmInline
value class TaskDetailActions(
    val block: (Action) -> Unit,
) {
    /** Sealed action hierarchy — enables exhaustive `when` with smart-cast. */
    sealed class Action {
        // ── Hero ─────────────────────────────────────────────────────────────
        data object ToggleComplete : Action()
        data class TitleChange(val title: String) : Action()
        data class DescriptionChange(val description: String) : Action()
        data object ToggleSomeday : Action()
        data object OpenKindPicker : Action()

        // ── Meta chips ───────────────────────────────────────────────────────
        data object OpenDatePicker : Action()
        data object OpenTimePicker : Action()
        data object OpenPriorityPicker : Action()
        data object OpenProjectPicker : Action()
        data class NavigateToProject(val id: ProjectId) : Action()
        data class NavigateToParent(val parentTaskId: TaskId) : Action()

        // ── Tags ─────────────────────────────────────────────────────────────
        data object AddTag : Action()
        data class RemoveTag(val id: TagId) : Action()

        // ── Checklist ────────────────────────────────────────────────────────
        data class ToggleChecklistItem(val item: ChecklistItem) : Action()
        data class DeleteChecklistItem(val id: ChecklistItemId) : Action()
        data class AddChecklistItem(val title: String) : Action()

        // ── Subtasks ─────────────────────────────────────────────────────────
        /** Navigate to a child task's detail screen. */
        data class NavigateToSubtask(val childTaskId: TaskId) : Action()
        /** Toggle a subtask's completion state. */
        data class ToggleSubtask(val task: Task) : Action()
        /** Delete a subtask. */
        data class DeleteSubtask(val task: Task) : Action()
        /** Promote a checklist item to a sub-task (removes checklist item, creates task). */
        data class PromoteChecklistToSubtask(val checklistItem: ChecklistItem) : Action()

        // ── Reminders ────────────────────────────────────────────────────────
        data class DeleteReminder(val reminder: Reminder) : Action()

        // ── Attachments ──────────────────────────────────────────────────────
        data class DeleteAttachment(val attachment: Attachment) : Action()
        /** Open an attachment (preview / download / share). */
        data class ClickAttachment(val attachment: Attachment) : Action()

        // ── Bottom bar ──────────────────────────────────────────────────────
        data object OpenReminderSheet : Action()
        data object OpenAttachmentSheet : Action()
        data object TogglePin : Action()
        data object OpenDeleteConfirm : Action()

        // ── Dialog ───────────────────────────────────────────────────────────
        data object OpenArchiveConfirm : Action()
    }

    // ── Hero ──────────────────────────────────────────────────────────────────

    fun onToggleComplete() = block(Action.ToggleComplete)
    fun onTitleChange(title: String) = block(Action.TitleChange(title))
    fun onDescriptionChange(description: String) = block(Action.DescriptionChange(description))
    fun onToggleSomeday() = block(Action.ToggleSomeday)
    fun onOpenKindPicker() = block(Action.OpenKindPicker)

    // ── Meta chips ───────────────────────────────────────────────────────────

    fun onPickDate() = block(Action.OpenDatePicker)
    fun onPickTime() = block(Action.OpenTimePicker)
    fun onPickPriority() = block(Action.OpenPriorityPicker)
    fun onPickProject() = block(Action.OpenProjectPicker)
    fun onNavigateToProject(id: ProjectId) = block(Action.NavigateToProject(id))
    fun onNavigateToParent(parentTaskId: TaskId) = block(Action.NavigateToParent(parentTaskId))

    // ── Tags ─────────────────────────────────────────────────────────────────

    fun onAddTag() = block(Action.AddTag)
    fun onRemoveTag(id: TagId) = block(Action.RemoveTag(id))

    // ── Checklist ───────────────────────────────────────────────────────────

    fun onToggleChecklistItem(item: ChecklistItem) = block(Action.ToggleChecklistItem(item))
    fun onDeleteChecklistItem(id: ChecklistItemId) = block(Action.DeleteChecklistItem(id))
    fun onAddChecklistItem(title: String) = block(Action.AddChecklistItem(title))

    // ── Subtasks ──────────────────────────────────────────────────────────────

    fun onNavigateToSubtask(childTaskId: TaskId) = block(Action.NavigateToSubtask(childTaskId))
    fun onToggleSubtask(task: Task) = block(Action.ToggleSubtask(task))
    fun onDeleteSubtask(task: Task) = block(Action.DeleteSubtask(task))
    fun onPromoteChecklistToSubtask(item: ChecklistItem) = block(Action.PromoteChecklistToSubtask(item))

    // ── Reminders ────────────────────────────────────────────────────────────

    fun onDeleteReminder(reminder: Reminder) = block(Action.DeleteReminder(reminder))

    // ── Attachments ──────────────────────────────────────────────────────────

    fun onDeleteAttachment(attachment: Attachment) = block(Action.DeleteAttachment(attachment))
    fun onClickAttachment(attachment: Attachment) = block(Action.ClickAttachment(attachment))

    // ── Bottom bar ────────────────────────────────────────────────────────────

    fun onRemind() = block(Action.OpenReminderSheet)
    fun onAttach() = block(Action.OpenAttachmentSheet)
    fun onPin() = block(Action.TogglePin)
    fun onDelete() = block(Action.OpenDeleteConfirm)

    // ── Dialog ───────────────────────────────────────────────────────────────

    fun onArchive() = block(Action.OpenArchiveConfirm)

    companion object {
        /** No-op actions — useful for previews and test stubs. */
        val Empty = TaskDetailActions {}
    }
}
