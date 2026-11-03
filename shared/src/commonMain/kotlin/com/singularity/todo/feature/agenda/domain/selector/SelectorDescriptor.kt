package com.singularity.todo.feature.agenda.domain.selector

import com.singularity.todo.core.format.formatPadded
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState

/**
 * Human-readable description of a [Selector] for UI display.
 *
 * This is the **single source of truth** for selector descriptions — never duplicate
 * the description logic elsewhere.
 *
 * ## Coverage
 *
 * All 19 Selector variants are covered: DateBucket, DateRange, Statuses,
 * Priorities, Tags, Projects, Pinned, Completed, Overdue, Blocked, WorkflowState,
 * FollowUp, Stagnant, Regexp, AllOf, AnyOf, Not, Anything, TimeOfDay.
 *
 * @see Selector
 */
val Selector.typeDescription: String
    get() =
        when (this) {
            is Selector.DateBucket -> {
                when (bucket) {
                    RelativeBucket.Overdue -> "Overdue tasks"
                    RelativeBucket.Today -> "Today"
                    RelativeBucket.Tomorrow -> "Tomorrow"
                    RelativeBucket.ThisWeek -> "This week"
                    RelativeBucket.NextWeek -> "Next week"
                    RelativeBucket.ThisMonth -> "This month"
                    RelativeBucket.NextMonth -> "Next month"
                    RelativeBucket.Yesterday -> "Yesterday"
                    RelativeBucket.NoDate -> "No date"
                }
            }

            is Selector.DateRange -> {
                "Date range: $from – $to"
            }

            is Selector.Statuses -> {
                val names = statuses.map { it.statusName }.sorted()
                when (names.size) {
                    1 -> names[0]
                    2 if statuses.contains(TaskStatus.Active) && statuses.contains(TaskStatus.Completed) -> "All"
                    else -> names.joinToString(", ")
                }
            }

            is Selector.Priorities -> {
                val prefix = if (atMost) "Priority: " else "Priority not: "
                val names = priorities.map { it.priorityName }.sorted()
                prefix + names.joinToString(", ")
            }

            is Selector.Tags -> {
                when {
                    matchAll -> "All tags: ${ids.size}"
                    else -> "Any tag: ${ids.size}"
                }
            }

            is Selector.Projects -> {
                when (ids.size) {
                    0 -> "No project"
                    1 -> "Project"
                    else -> "${ids.size} projects"
                }
            }

            is Selector.Pinned -> {
                "Pinned"
            }

            is Selector.Completed -> {
                "Completed"
            }

            is Selector.Overdue -> {
                "Overdue"
            }

            is Selector.Blocked -> {
                "Blocked"
            }

            is Selector.WorkflowState -> {
                when (state) {
                    TaskWorkflowState.Open -> "No state"
                    TaskWorkflowState.NextUp -> "Next up"
                    TaskWorkflowState.InProgress -> "In progress"
                }
            }

            is Selector.FollowUp -> {
                "Follow-ups"
            }

            is Selector.Stagnant -> {
                "Stagnant"
            }

            is Selector.Regexp -> {
                "Regex: $query"
            }

            is Selector.AllOf -> {
                "All of (${children.size} rules)"
            }

            is Selector.AnyOf -> {
                "Any of (${children.size} rules)"
            }

            is Selector.Not -> {
                "Not: ${child.typeDescription}"
            }

            is Selector.Anything -> {
                "All tasks"
            }

            is Selector.TimeOfDay -> {
                "Work block: ${minutesOfDay(minStartMinutes)}–${minutesOfDay(maxEndMinutes)}"
            }
        }

/**
 * Minutes from midnight as a clock: `540` → "09:00".
 *
 * The bounds are the user's own work-schedule minutes, and the agenda preset
 * moves each end by a minute: with a lunch break at midnight the start
 * becomes −1, and with a lunch break that ends at 23:59 the end becomes
 * 1440. Neither is a time of day, so neither is printed as one — the label
 * reads "00:00" and "24:00".
 */
private fun minutesOfDay(minutes: Int): String {
    val clamped = minutes.coerceIn(0, 24 * 60)
    return "${formatPadded(clamped / 60)}:${formatPadded(clamped % 60)}"
}

/** Name for [TaskStatus] display. */
private val TaskStatus.statusName: String
    get() =
        when (this) {
            TaskStatus.Active -> "Active"
            TaskStatus.Completed -> "Completed"
            TaskStatus.All -> "All"
        }

/** Name for [TaskPriority] display. */
private val TaskPriority.priorityName: String
    get() =
        when (this) {
            TaskPriority.High -> "High"
            TaskPriority.Medium -> "Medium"
            TaskPriority.Low -> "Low"
            TaskPriority.None -> "None"
            TaskPriority.Urgent -> "Urgent"
        }
