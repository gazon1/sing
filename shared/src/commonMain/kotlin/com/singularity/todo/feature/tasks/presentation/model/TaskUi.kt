package com.singularity.todo.feature.tasks.presentation.model

import com.singularity.todo.core.ui.components.formatRussianDueDate
import com.singularity.todo.feature.tasks.domain.logic.computeTaskMarkings
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskMarking
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableSet
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
 * ## Why the dependency set is persistent, and why the annotation is gone
 *
 * [blockedBy] was a `Set<TaskId>`, and the class carried `@Immutable`. The
 * annotation was a claim the compiler could not check: a `Set` is an interface,
 * so the compiler could not treat the value as stable, and `@Immutable` only
 * made the *comparison* deep — every `equals` on a `TaskUi` walked the whole
 * dependency set.
 *
 * [ImmutableSet] is a known-stable construct for the Compose compiler, so the
 * type now says what the annotation used to claim. The annotation is gone
 * because it is no longer needed and because an annotation that has to be
 * *true* is not a decoration: it is a promise about a type.
 *
 * ## Pre-MR-1 cleanup
 *
 * [domainTask] was removed — [isPinned] is now a direct field so the
 * menu builder no longer needs a domain reference. The AI bottom sheet
 * (future work) will look up the [Task] by [id] from the repository.
 *
 * ## Why the markings are a set and not three booleans
 *
 * The row used to carry `isBlocked`, `isRecurring` and `isOverdue` — three
 * separate facts that the row happened to display, and three separate places
 * for a fourth fact to be forgotten. They are now one set of
 * [TaskMarking]s, computed by the shared policy, so the agenda and the task
 * list answer "what is this task marked with?" the same way.
 *
 * The two fields that survived — [isCompleted] and [isPinned] — are the ones
 * the UI *acts* on rather than merely shows: the checkbox toggles completion
 * and the context menu offers to unpin. A field the UI acts on belongs to the
 * model; a field it only shows belongs to the marking.
 *
 * @see 2026-09-18-task-dependencies
 * @see 2026-10-22-compose-stability-contract
 */
data class TaskUi(
    val id: TaskId,
    val title: String,
    val project: String?, // null -> no badge
    /**
     * The project's ARGB colour, or `null` when there is no project.
     *
     * Resolved once per list build, beside the name, so the row never looks a
     * project up. The Compose conversion is `Color(projectColor)`.
     */
    val projectColor: Int? = null,
    val dueLabel: String?, // pre-formatted date: "Сб, 05 сент 2026"
    val parentId: String? = null, // null = top-level, otherwise parent TaskId.value
    val indentLevel: Int = 0, // 0 for top-level, 1 for direct child
    val isPinned: Boolean = false, // MR-1: lifted from domainTask to avoid reference
    val priority: TaskPriority = TaskPriority.None,
    val isCompleted: Boolean = false,
    val isSelected: Boolean = false,
    /** MR-1: IDs of tasks that must be completed before this task. */
    val blockedBy: ImmutableSet<TaskId> = persistentSetOf(),
    /**
     * Everything this task is marked with — blocked, follow-up, in progress,
     * next up, overdue, no date, pinned, repeating, completed.
     *
     * A set, because a task can carry several of these at once and none of
     * them may hide another.
     */
    val markings: ImmutableSet<TaskMarking> = persistentSetOf(),
    /** How many dates the series of this row has; null when it stands alone. */
    val seriesDates: Int? = null,
) {
    // domainTask reference removed — AI actions future work will use repository lookup by id
}

/**
 * Converts a domain [Task] to a [TaskUi].
 *
 * [today] is used to compute the overdue marking and to resolve the date
 * buckets. [projectNamesById] maps
 * [ProjectId.value][com.singularity.todo.feature.projects.domain.model.ProjectId.value] to
 * the project name string.
 * [blockedBy] and [isBlocked] are passed from the list-building context — they default
 * to the task's own field and `false` respectively when the full task list is not available.
 */
fun Task.toTaskUi(
    today: LocalDate,
    projectNamesById: Map<String, String>,
    projectColorsById: Map<String, Int> = emptyMap(),
    blockedBy: Set<TaskId> = this.blockedBy,
    isBlocked: Boolean = false,
): TaskUi = TaskUi(
    id = this.id,
    title = this.title,
    project = this.projectId?.value?.let { projectNamesById[it] },
    projectColor = this.projectId?.value?.let { projectColorsById[it] },
    dueLabel = formatRussianDueDate(this.dueDate),
    parentId = this.parentTaskId?.value,
    indentLevel = if (this.parentTaskId != null) 1 else 0,
    isCompleted = this.completedAt != null,
    isPinned = this.isPinned,
    priority = this.priority,
    blockedBy = blockedBy.toImmutableSet(),
    markings = computeTaskMarkings(this, today, isBlocked),
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
