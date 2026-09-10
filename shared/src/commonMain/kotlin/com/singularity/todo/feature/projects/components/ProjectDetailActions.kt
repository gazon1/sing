package com.singularity.todo.feature.projects.components

import com.singularity.todo.feature.projects.ProjectDetailIntent
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * All callbacks for [com.singularity.todo.feature.projects.ProjectDetailContent].
 * Packed into a [JvmInline value class][value class] so each section receives
 * one parameter instead of 5–11 individual lambdas.
 *
 * Routing variants ([ProjectDetailIntent.Routing]) are handled by the screen directly
 * (no VM call needed). Domain variants are dispatched to [ProjectDetailViewModel.onIntent].
 *
 * Screen dispatcher:
 * ```kotlin
 * ProjectDetailActions { intent ->
 *     when (intent) {
 *         is ProjectDetailIntent.Routing.NavigateToTasks -> onNavigateToTasks(intent.projectId)
 *         is ProjectDetailIntent.Routing.NavigateToTask  -> onNavigateToTask(intent.taskId)
 *         is ProjectDetailIntent.Domain -> viewModel.onIntent(intent)
 *     }
 * }
 * ```
 *
 * @see ProjectDetailIntent — исчерпывающий список всех операций.
 */
@JvmInline
value class ProjectDetailActions(
    private val block: (ProjectDetailIntent) -> Unit,
) {

    // ── Routing ──────────────────────────────────────────────────────────────

    /** Navigate to the task list for this project. */
    fun onNavigateToTasks(projectId: ProjectId) =
        block(ProjectDetailIntent.Routing.NavigateToTasks(projectId))

    /** Navigate to a specific task's detail screen. */
    fun onNavigateToTask(taskId: TaskId) =
        block(ProjectDetailIntent.Routing.NavigateToTask(taskId))

    // ── Sheet openers ───────────────────────────────────────────────────────

    fun onOpenColorSheet() = block(ProjectDetailIntent.Routing.OpenColorSheet)
    fun onOpenIconSheet() = block(ProjectDetailIntent.Routing.OpenIconSheet)
    fun onOpenParentSheet(currentParentId: ProjectId?) =
        block(ProjectDetailIntent.Routing.OpenParentSheet(currentParentId))
    fun onOpenDueDateSheet() = block(ProjectDetailIntent.Routing.OpenDueDateSheet)
    fun onOpenChildrenSheet() = block(ProjectDetailIntent.Routing.OpenChildrenSheet)
    fun onOpenDeleteSheet() = block(ProjectDetailIntent.Routing.OpenDeleteSheet)
    fun onOpenArchiveSheet() = block(ProjectDetailIntent.Routing.OpenArchiveSheet)
    fun onOpenReminderSheet() = block(ProjectDetailIntent.Routing.OpenReminderSheet)
    fun onOpenAttachmentSheet() = block(ProjectDetailIntent.Routing.OpenAttachmentSheet)

    // ── Visibility ──────────────────────────────────────────────────────────

    fun onToggleHideCompleted() =
        block(ProjectDetailIntent.Domain.ToggleHideCompleted)

    // ── Inline edits ────────────────────────────────────────────────────────

    fun onUpdateName(name: String) =
        block(ProjectDetailIntent.Domain.UpdateName(name))

    fun onUpdateDescription(description: String?) =
        block(ProjectDetailIntent.Domain.UpdateDescription(description))

    // ── Pickers ─────────────────────────────────────────────────────────────

    fun onUpdateColor(color: Int) =
        block(ProjectDetailIntent.Domain.UpdateColor(color))

    fun onUpdateIcon(icon: String?) =
        block(ProjectDetailIntent.Domain.UpdateIcon(icon))

    fun onUpdateParent(parentId: ProjectId?) =
        block(ProjectDetailIntent.Domain.UpdateParent(parentId))

    fun onUpdateDueDate(dueDate: kotlinx.datetime.LocalDate?) =
        block(ProjectDetailIntent.Domain.UpdateDueDate(dueDate))

    // ── Lifecycle ───────────────────────────────────────────────────────────

    fun onToggleArchive() =
        block(ProjectDetailIntent.Domain.ToggleArchive)

    fun onDelete() =
        block(ProjectDetailIntent.Domain.Delete)

    // ── Tasks ─────────────────────────────────────────────────────────────

    fun onCreateTask(title: String) =
        block(ProjectDetailIntent.Domain.CreateTask(title))

    fun onMoveTaskToProject(taskId: TaskId) =
        block(ProjectDetailIntent.Domain.MoveTaskToProject(taskId))

    companion object {
        /** No-op actions — для превью и тестов. */
        val Empty = ProjectDetailActions {}
    }
}
