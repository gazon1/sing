package com.singularity.todo.feature.tasks.presentation.model

import androidx.compose.runtime.Immutable
import com.singularity.todo.core.ui.components.formatRussianDueDate
import com.singularity.todo.feature.tasks.domain.logic.TaskComputed
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate

/**
 * Immutable UI model of a task.
 *
 * Separated from the domain/network model — the screen only needs
 * what it needs for rendering, not the full domain object.
 *
 * Field [isOverdue] is computed in [toTaskUi] based on [dueDate] and [completedAt],
 * keeping the UI layer free of date logic.
 *
 * ## Pre-MR-1 cleanup
 *
 * [domainTask] was removed — [isPinned] is now a direct field so the
 * menu builder no longer needs a domain reference. The AI bottom sheet
 * (future work) will look up the [Task] by [id] from the repository.
 *
 * @see 2026-09-18-task-dependencies
 */
@Immutable
data class TaskUi(
    val id: TaskId,
    val title: String,
    val project: String?, // null -> "Без проекта"
    val dueLabel: String?, // pre-formatted date: "Сб, 05 сент 2026"
    val parentId: String? = null, // null = top-level, otherwise parent TaskId.value
    val indentLevel: Int = 0, // 0 for top-level, 1 for direct child
    val isRecurring: Boolean = false,
    val isPinned: Boolean = false, // MR-1: lifted from domainTask to avoid reference
    val priority: TaskPriority = TaskPriority.None,
    val isCompleted: Boolean = false,
    val isOverdue: Boolean = false,
    val isSelected: Boolean = false,
    /** MR-1: IDs of tasks that must be completed before this task. */
    val dependsOn: Set<TaskId> = emptySet(),
    /** MR-1: true when at least one dependency is not yet completed. */
    val isBlocked: Boolean = false,
) {
    // domainTask reference removed — AI actions future work will use repository lookup by id
}

/**
 * Converts a domain [Task] to a [TaskUi].
 *
 * [today] is used to compute [isOverdue]. [projectNamesById] maps
 * [ProjectId.value][com.singularity.todo.feature.projects.domain.model.ProjectId.value] to
 * the project name string.
 * [dependsOn] and [isBlocked] are passed from the list-building context — they default
 * to the task's own field and `false` respectively when the full task list is not available.
 */
fun Task.toTaskUi(
    today: LocalDate,
    projectNamesById: Map<String, String>,
    dependsOn: Set<TaskId> = this.dependsOn,
    isBlocked: Boolean = false,
): TaskUi = TaskUi(
    id = this.id,
    title = this.title,
    project = this.projectId?.value?.let { projectNamesById[it] },
    dueLabel = formatRussianDueDate(this.dueDate),
    parentId = this.parentTaskId?.value,
    indentLevel = if (this.parentTaskId != null) 1 else 0,
    isCompleted = this.completedAt != null,
    isOverdue = TaskComputed.isOverdue(this, today),
    isPinned = this.isPinned,
    priority = this.priority,
    dependsOn = dependsOn,
    isBlocked = isBlocked,
)

/** Counters for the header / filter chips — computed from the list once. */
data class TaskListStats(val total: Int, val active: Int, val completed: Int) {
    companion object {
        fun from(tasks: List<TaskUi>): TaskListStats = TaskListStats(
            total = tasks.size,
            active = tasks.count { !it.isCompleted },
            completed = tasks.count { it.isCompleted },
        )
    }
}
