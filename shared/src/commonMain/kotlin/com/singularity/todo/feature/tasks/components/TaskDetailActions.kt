package com.singularity.todo.feature.tasks.components

import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.settings.ReminderOffset
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.ActiveSheet
import com.singularity.todo.feature.tasks.Task
import com.singularity.todo.feature.tasks.TaskDetailIntent
import com.singularity.todo.feature.tasks.TaskId
import com.singularity.todo.feature.tasks.TaskKind
import com.singularity.todo.feature.tasks.TaskPriority
import kotlinx.datetime.LocalDate

/**
 * Все callbacks доступные в секциях экрана [com.singularity.todo.feature.tasks.TaskDetailScreen].
 * Упакованы в [JvmInline value class][value class], чтобы каждая секция получала
 * один параметр `actions` вместо 10–27 отдельных лямбд.
 *
 * Внутренне [block] получает [TaskDetailIntent]: routing-варианты обрабатываются
 * экраном, доменные — [com.singularity.todo.feature.tasks.TaskDetailViewModel.onIntent].
 *
 * Экранный диспетчер:
 * ```kotlin
 * TaskDetailActions { intent ->
 *     when (intent) {
 *         is TaskDetailIntent.OpenSheet      -> activeSheet = intent.sheet
 *         TaskDetailIntent.CloseSheet       -> activeSheet = null
 *         is TaskDetailIntent.NavigateToTask -> onNavigateToTask(intent.id)
 *         is TaskDetailIntent.NavigateToProject -> onNavigateToProject(intent.id)
 *         is TaskDetailIntent.Attachment     -> handleAttachment(intent, attachmentsVm)
 *         is TaskDetailIntent.Domain         -> viewModel.onIntent(intent)
 *     }
 * }
 * ```
 *
 * 27 callbacks → 1 параметр. Хелперы публичные для секций; тип блока — деталь реализации.
 *
 * @see TaskDetailIntent.Domain — исчерпывающий список всех доменных операций.
 */
@JvmInline
value class TaskDetailActions(
    private val block: (TaskDetailIntent) -> Unit,
) {

    // ── Hero ──────────────────────────────────────────────────────────────────

    fun onToggleComplete() = block(TaskDetailIntent.Domain.ToggleComplete)
    fun onTitleChange(title: String) = block(TaskDetailIntent.Domain.TitleChanged(title))
    fun onDescriptionChange(description: String) = block(TaskDetailIntent.Domain.DescriptionChanged(description))
    fun onToggleSomeday() = block(TaskDetailIntent.Domain.ToggleSomeday)
    fun onOpenKindPicker() = block(TaskDetailIntent.OpenSheet(ActiveSheet.Kind))

    // ── Meta chips ─────────────────────────────────────────────────────────────

    fun onPickDate() = block(TaskDetailIntent.OpenSheet(ActiveSheet.Date))
    fun onPickTime() = block(TaskDetailIntent.OpenSheet(ActiveSheet.Time))
    fun onPickPriority() = block(TaskDetailIntent.OpenSheet(ActiveSheet.Priority))
    fun onPickProject() = block(TaskDetailIntent.OpenSheet(ActiveSheet.Project))
    fun onNavigateToProject(id: ProjectId) = block(TaskDetailIntent.NavigateToProject(id))
    fun onNavigateToParent(parentTaskId: TaskId) = block(TaskDetailIntent.NavigateToTask(parentTaskId))

    // ── Tags ──────────────────────────────────────────────────────────────────

    fun onAddTag() = block(TaskDetailIntent.OpenSheet(ActiveSheet.Tags))
    fun onRemoveTag(id: TagId) = block(TaskDetailIntent.Domain.RemoveTag(id))

    // ── Checklist ──────────────────────────────────────────────────────────────

    fun onToggleChecklistItem(item: ChecklistItem) = block(TaskDetailIntent.Domain.ToggleChecklistItem(item))
    fun onDeleteChecklistItem(id: ChecklistItemId) = block(TaskDetailIntent.Domain.DeleteChecklistItem(id))
    fun onAddChecklistItem(title: String) = block(TaskDetailIntent.Domain.AddChecklistItem(title))

    // ── Subtasks ───────────────────────────────────────────────────────────────

    fun onNavigateToSubtask(childTaskId: TaskId) = block(TaskDetailIntent.NavigateToTask(childTaskId))
    fun onToggleSubtask(task: Task) = block(TaskDetailIntent.Domain.ToggleSubtask(task))
    fun onDeleteSubtask(task: Task) = block(TaskDetailIntent.Domain.DeleteSubtask(task))
    fun onPromoteChecklistToSubtask(item: ChecklistItem) =
        block(TaskDetailIntent.Domain.AddSubtask(item.title))

    // ── Reminders ──────────────────────────────────────────────────────────────

    fun onDeleteReminder(reminder: Reminder) = block(TaskDetailIntent.Domain.DeleteReminder)
    fun onRemind() = block(TaskDetailIntent.OpenSheet(ActiveSheet.Reminder))

    // ── Attachments ─────────────────────────────────────────────────────────────

    fun onDeleteAttachment(attachment: Attachment) =
        block(TaskDetailIntent.Attachment.Delete(attachment.id))
    fun onClickAttachment(attachment: Attachment) =
        block(TaskDetailIntent.Attachment.Click(attachment))
    fun onAttach() = block(TaskDetailIntent.OpenSheet(ActiveSheet.Attachment))

    // ── Bottom bar ─────────────────────────────────────────────────────────────

    fun onPin() = block(TaskDetailIntent.Domain.TogglePinned)
    fun onDelete() = block(TaskDetailIntent.OpenSheet(ActiveSheet.ConfirmDelete))
    fun onArchive() = block(TaskDetailIntent.OpenSheet(ActiveSheet.ConfirmArchive))

    companion object {
        /** No-op actions — для превью и тестов. */
        val Empty = TaskDetailActions {}
    }
}
