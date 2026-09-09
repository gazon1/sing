package com.singularity.todo.feature.tasks.components

import com.singularity.todo.feature.tags.TagId
import kotlinx.datetime.LocalDate

/**
 * All action callbacks available in the [TaskEditorContent].
 * Packed into a single [JvmInline value class][value class] so the composable
 * receives exactly one `actions` parameter instead of 16 individual lambdas.
 *
 * Grouped as:
 * - **Hero**: title, description
 * - **Priority/Kind**: open priority picker, open kind picker
 * - **Date/Time**: date picker, time picker, date presets, clear date/time
 * - **Organization**: project picker, tags picker, clear project, remove tag
 * - **Checklist**: new item text, add item, toggle item, delete item
 * - **Reminder**: open reminder picker
 * - **Attachments**: open attachment picker, remove attachment
 *
 * ## Usage
 *
 * Instead of 16 callback parameters:
 * ```kotlin
 * TaskEditorContent(
 *     state = state,
 *     onTitleChange = { ... },
 *     onDescriptionChange = { ... },
 *     onPriorityClick = { ... },
 *     // ... 13 more
 * )
 * ```
 *
 * Use one actions parameter:
 * ```kotlin
 * TaskEditorContent(
 *     state = state,
 *     actions = TaskEditorActions { action ->
 *         when (action) {
 *             is Action.TitleChange -> viewModel.setTitle(action.title)
 *             is Action.DescriptionChange -> viewModel.setDescription(action.description)
 *             // ...
 *         }
 *     },
 * )
 * ```
 *
 * @see TaskDetailActions — larger example with 27 callbacks in TaskDetailScreen
 */
@JvmInline
value class TaskEditorActions(
    val block: (Action) -> Unit,
) {
    /** Sealed action hierarchy — enables exhaustive `when` with smart-cast. */
    sealed class Action {
        // ── Hero ──────────────────────────────────────────────────────────────
        data class TitleChange(val title: String) : Action()
        data class DescriptionChange(val description: String) : Action()

        // ── Priority / Kind ──────────────────────────────────────────────────
        data object OpenPriorityPicker : Action()
        data object OpenKindPicker : Action()

        // ── Date / Time ─────────────────────────────────────────────────────
        data object OpenDatePicker : Action()
        data object OpenTimePicker : Action()
        data class DatePreset(val date: LocalDate) : Action()
        data object ClearDate : Action()
        data object ClearTime : Action()

        // ── Organization ─────────────────────────────────────────────────────
        data object OpenProjectPicker : Action()
        data object OpenTagsPicker : Action()
        data object ClearProject : Action()
        data class RemoveTag(val tagId: TagId) : Action()

        // ── Checklist ──────────────────────────────────────────────────────
        data class NewChecklistItemChange(val text: String) : Action()
        data object AddChecklistItem : Action()
        data class ToggleChecklistItem(val id: String) : Action()
        data class DeleteChecklistItem(val id: String) : Action()

        // ── Reminder ────────────────────────────────────────────────────────
        data object OpenReminderPicker : Action()

        // ── Attachments ─────────────────────────────────────────────────────
        data object OpenAttachmentPicker : Action()
        data class RemoveAttachment(val id: String) : Action()
    }

    // ── Hero ─────────────────────────────────────────────────────────────────

    fun onTitleChange(title: String) = block(Action.TitleChange(title))
    fun onDescriptionChange(description: String) = block(Action.DescriptionChange(description))

    // ── Priority / Kind ──────────────────────────────────────────────────────

    fun onOpenPriorityPicker() = block(Action.OpenPriorityPicker)
    fun onOpenKindPicker() = block(Action.OpenKindPicker)

    // ── Date / Time ──────────────────────────────────────────────────────────

    fun onOpenDatePicker() = block(Action.OpenDatePicker)
    fun onOpenTimePicker() = block(Action.OpenTimePicker)
    fun onDatePreset(date: LocalDate) = block(Action.DatePreset(date))
    fun onClearDate() = block(Action.ClearDate)
    fun onClearTime() = block(Action.ClearTime)

    // ── Organization ─────────────────────────────────────────────────────────

    fun onOpenProjectPicker() = block(Action.OpenProjectPicker)
    fun onOpenTagsPicker() = block(Action.OpenTagsPicker)
    fun onClearProject() = block(Action.ClearProject)
    fun onRemoveTag(tagId: TagId) = block(Action.RemoveTag(tagId))

    // ── Checklist ───────────────────────────────────────────────────────────

    fun onNewChecklistItemChange(text: String) = block(Action.NewChecklistItemChange(text))
    fun onAddChecklistItem() = block(Action.AddChecklistItem)
    fun onToggleChecklistItem(id: String) = block(Action.ToggleChecklistItem(id))
    fun onDeleteChecklistItem(id: String) = block(Action.DeleteChecklistItem(id))

    // ── Reminder ─────────────────────────────────────────────────────────────

    fun onOpenReminderPicker() = block(Action.OpenReminderPicker)

    // ── Attachments ──────────────────────────────────────────────────────────

    fun onOpenAttachmentPicker() = block(Action.OpenAttachmentPicker)
    fun onRemoveAttachment(id: String) = block(Action.RemoveAttachment(id))

    companion object {
        /** No-op actions — useful for previews and test stubs. */
        val Empty = TaskEditorActions {}
    }
}
