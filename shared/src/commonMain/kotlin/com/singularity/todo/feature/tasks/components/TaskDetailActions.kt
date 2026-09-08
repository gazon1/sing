package com.singularity.todo.feature.tasks.components

import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tags.TagId

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
    enum class Action {
        // ── Hero ──────────────────────────────────────────────────────────────
        ToggleComplete,
        TitleChange,
        DescriptionChange,

        // ── Meta chips ────────────────────────────────────────────────────────
        OpenDatePicker,
        OpenTimePicker,
        OpenPriorityPicker,
        OpenProjectPicker,
        NavigateToProject,

        // ── Tags ──────────────────────────────────────────────────────────────
        AddTag,
        RemoveTag,

        // ── Checklist ─────────────────────────────────────────────────────────
        ToggleChecklistItem,
        DeleteChecklistItem,
        AddChecklistItem,

        // ── Bottom bar ────────────────────────────────────────────────────────
        OpenReminderSheet,
        OpenAttachmentSheet,
        TogglePin,
        OpenDeleteConfirm,

        // ── Dialog ────────────────────────────────────────────────────────────
        OpenArchiveConfirm,
    }

    // ── Hero ──────────────────────────────────────────────────────────────────

    fun onToggleComplete() = block(Action.ToggleComplete)
    fun onTitleChange(title: String) = block(Action.TitleChange)
    fun onDescriptionChange(description: String) = block(Action.DescriptionChange)

    // ── Meta chips ───────────────────────────────────────────────────────────

    fun onPickDate() = block(Action.OpenDatePicker)
    fun onPickTime() = block(Action.OpenTimePicker)
    fun onPickPriority() = block(Action.OpenPriorityPicker)
    fun onPickProject() = block(Action.OpenProjectPicker)
    fun onNavigateToProject(id: ProjectId) = block(Action.NavigateToProject)

    // ── Tags ─────────────────────────────────────────────────────────────────

    fun onAddTag() = block(Action.AddTag)
    fun onRemoveTag(id: TagId) = block(Action.RemoveTag)

    // ── Checklist ────────────────────────────────────────────────────────────

    fun onToggleChecklistItem(item: ChecklistItem) = block(Action.ToggleChecklistItem)
    fun onDeleteChecklistItem(id: ChecklistItemId) = block(Action.DeleteChecklistItem)
    fun onAddChecklistItem(title: String) = block(Action.AddChecklistItem)

    // ── Bottom bar ────────────────────────────────────────────────────────────

    fun onRemind() = block(Action.OpenReminderSheet)
    fun onAttach() = block(Action.OpenAttachmentSheet)
    fun onPin() = block(Action.TogglePin)
    fun onDelete() = block(Action.OpenDeleteConfirm)

    // ── Dialog ────────────────────────────────────────────────────────────────

    fun onArchive() = block(Action.OpenArchiveConfirm)

    companion object {
        /** No-op actions — useful for previews and test stubs. */
        val Empty = TaskDetailActions {}
    }
}
