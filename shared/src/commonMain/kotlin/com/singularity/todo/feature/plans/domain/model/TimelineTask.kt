package com.singularity.todo.feature.plans.domain.model

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskMarking
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf

/**
 * One task as the timeline shows it.
 *
 * The task itself plus what is marked on it — the same markings the agenda
 * shows, computed by the one shared policy, so a task looks the same in both
 * places.
 *
 * @param task The task.
 * @param markings Everything the task is marked with: blocked, overdue, pinned
 *   and the rest. A set, not a single badge — a task can be several of them.
 * @param seriesDates How many dates the series of this row has; null when it
 *   stands alone.
 */
data class TimelineTask(
    val task: Task,
    val markings: ImmutableSet<TaskMarking> = persistentSetOf(),
    val seriesDates: Int? = null,
)
