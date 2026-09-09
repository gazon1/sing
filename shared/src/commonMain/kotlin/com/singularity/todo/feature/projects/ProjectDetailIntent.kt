package com.singularity.todo.feature.projects

import com.singularity.todo.feature.tasks.TaskId

/**
 * Единая точка входа для [ProjectDetailViewModel].
 *
 * Routing-варианты (навигация) обрабатываются экраном,
 * доменные — [ProjectDetailViewModel.onIntent].
 *
 * Разделение типизировано на уровне sealed-иерархии: попытка передать
 * routing-интент в VM — ошибка компиляции.
 *
 * @see TaskDetailIntent] — аналогичный паттерн для TaskDetailScreen.
 */
sealed interface ProjectDetailIntent {

    // ── Routing: owned by screen ────────────────────────────────────────────

    sealed interface Routing : ProjectDetailIntent {
        data class NavigateToTasks(val projectId: ProjectId) : Routing
        data class NavigateToTask(val taskId: TaskId) : Routing
        // Sheet openers — screen sets activeSheet routing state
        data object OpenColorSheet : Routing
        data object OpenIconSheet : Routing
        data class OpenParentSheet(val currentParentId: ProjectId?) : Routing
        data object OpenDueDateSheet : Routing
        data object OpenChildrenSheet : Routing
        data object OpenDeleteSheet : Routing
        data object OpenArchiveSheet : Routing
        data object OpenReminderSheet : Routing
        data object OpenAttachmentSheet : Routing
    }

    // ── Domain: owned by ViewModel ──────────────────────────────────────────

    sealed interface Domain : ProjectDetailIntent {

        // ── Visibility ─────────────────────────────────────────────────────

        data object ToggleHideCompleted : Domain

        // ── Inline edits (debounced in VM) ────────────────────────────────

        data class UpdateName(val name: String) : Domain
        data class UpdateDescription(val description: String?) : Domain

        // ── Pickers ───────────────────────────────────────────────────────

        data class UpdateColor(val color: Int) : Domain
        data class UpdateIcon(val icon: String?) : Domain
        data class UpdateParent(val parentId: ProjectId?) : Domain
        data class UpdateDueDate(val dueDate: kotlinx.datetime.LocalDate?) : Domain

        // ── Lifecycle ─────────────────────────────────────────────────────

        data object ToggleArchive : Domain
        data object Delete : Domain

        // ── Tasks ─────────────────────────────────────────────────────────

        data class CreateTask(val title: String) : Domain
        data class MoveTaskToProject(val taskId: TaskId) : Domain
    }
}
