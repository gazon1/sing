package com.singularity.todo.feature.projects.presentation.state

import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * Единая точка входа для [com.singularity.todo.feature.projects.presentation.viewmodel.ProjectDetailViewModel].
 *
 * Routing-варианты (навигация) обрабатываются экраном,
 * доменные — [com.singularity.todo.feature.projects.presentation.viewmodel.ProjectDetailViewModel.onIntent].
 *
 * Разделение типизировано на уровне sealed-иерархии: попытка передать
 * routing-интент в VM — ошибка компиляции.
 *
 * @see com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent] — аналогичный паттерн для TaskDetailScreen.
 */
sealed interface ProjectDetailIntent : MviIntent {

    // ── Routing: owned by screen ────────────────────────────────────────────

    sealed interface Routing : ProjectDetailIntent {
        // Sheet openers — screen sets activeSheet routing state
        data object OpenColorSheet : Routing
        data object OpenIconSheet : Routing
        data class OpenParentSheet(val currentParentId: com.singularity.todo.feature.projects.domain.model.ProjectId?) :
            Routing
        data object OpenDueDateSheet : Routing
        data object OpenChildrenSheet : Routing
        data object OpenDeleteSheet : Routing
        data object OpenArchiveSheet : Routing
        data object OpenReminderSheet : Routing
        data object OpenAttachmentSheet : Routing
        data class NavigateToChild(val projectId: com.singularity.todo.feature.projects.domain.model.ProjectId) :
            Routing
    }

    // ── Domain: owned by ViewModel ──────────────────────────────────────────

    sealed interface Domain : ProjectDetailIntent {

        // ── Visibility ─────────────────────────────────────────────────────

        data object ToggleHideCompleted : Domain

        /**
         * Show or hide tasks that are blocked by unfinished dependencies.
         *
         * Continuous state, not a one-shot event — the screen reflects the
         * current value rather than acting once and resetting.
         */
        data object ToggleHideBlocked : Domain

        /**
         * Set or clear the project's reminder.
         *
         * [offsetMinutes] is minutes before the project due date, or null to remove the
         * reminder. The due date is the anchor because a project reminder exists to
         * fire when the project is due — an absolute instant would silently go stale
         * the moment the due date is edited.
         */
        data class SetReminder(val offsetMinutes: Int?) : Domain

        // ── Inline edits (debounced in VM) ────────────────────────────────

        data class UpdateName(val name: String) : Domain
        data class UpdateDescription(val description: String?) : Domain

        // ── Pickers ───────────────────────────────────────────────────────

        data class UpdateColor(val color: Int) : Domain
        data class UpdateIcon(val icon: String?) : Domain
        data class UpdateParent(val parentId: com.singularity.todo.feature.projects.domain.model.ProjectId?) : Domain
        data class UpdateDueDate(val dueDate: kotlinx.datetime.LocalDate?) : Domain

        // ── Lifecycle ─────────────────────────────────────────────────────

        data object ToggleArchive : Domain
        data object Delete : Domain

        // ── Tasks ─────────────────────────────────────────────────────────

        data class CreateTask(val title: String) : Domain
        data class MoveTaskToProject(val taskId: TaskId) : Domain
        data class ToggleTaskPin(val taskId: TaskId) : Domain
        data class DeleteTask(val taskId: TaskId) : Domain
    }
}
