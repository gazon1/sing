package com.singularity.todo.feature.tasks.presentation.model

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
 */
data class TaskUi(
    val id: TaskId,
    val title: String,
    val project: String?, // null -> "Без проекта"
    val dueLabel: String?, // pre-formatted date: "Сб, 05 сент 2026"
    val parentId: String? = null, // null = top-level, otherwise parent TaskId.value
    val indentLevel: Int = 0, // 0 for top-level, 1 for direct child
    val isRecurring: Boolean = false,
    val priority: TaskPriority = TaskPriority.None,
    val isCompleted: Boolean = false,
    val isOverdue: Boolean = false,
    val isSelected: Boolean = false,
    /**
     * The underlying domain [Task]. Stored here so the AI bottom sheet can
     * call [TasksViewModel.runAiAction] which requires a [Task], not a [TaskUi].
     * Also enables undo — [TasksViewModel.restore] only needs [TaskUi.id].
     */
    val domainTask: Task? = null,
)

/**
 * Converts a domain [Task] to a [TaskUi].
 *
 * [today] is used to compute [isOverdue]. [projectNamesById] maps
 * [ProjectId.value][com.singularity.todo.feature.projects.domain.model.ProjectId.value] to
 * the project name string.
 */
fun Task.toTaskUi(today: LocalDate, projectNamesById: Map<String, String>): TaskUi = TaskUi(
    id = this.id,
    title = this.title,
    project = this.projectId?.value?.let { projectNamesById[it] },
    dueLabel = formatRussianDueDate(this.dueDate),
    parentId = this.parentTaskId?.value,
    indentLevel = if (this.parentTaskId != null) 1 else 0,
    isCompleted = this.completedAt != null,
    isOverdue = TaskComputed.isOverdue(this, today),
    priority = this.priority,
    domainTask = this,
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
