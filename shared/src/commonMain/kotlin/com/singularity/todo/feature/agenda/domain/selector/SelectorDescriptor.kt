package com.singularity.todo.feature.agenda.domain.selector

import com.singularity.todo.core.format.formatPadded
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState

/** Minutes in a day: the day has 1440 of them, numbered 0..1439. */
private const val MINUTES_IN_DAY = 24 * 60

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
                workBlockLabel(minStartMinutes, maxEndMinutes)
            }
        }

/**
 * A work block as a clock range: "09:00–12:59".
 *
 * The bounds are minutes of the day, and a day has 1440 of them, numbered
 * 0..1439. The agenda preset derives each bound from the user's own
 * work-schedule minutes by moving it one minute, so a lunch break at midnight
 * makes a bound −1 and a lunch break that ends at 23:59 makes a bound 1440.
 * Neither is a minute of the day: −1 is the minute before the first one, and
 * 1440 is the midnight after the last one — "24:00" is not a clock. Both are
 * pulled into the day, and an end that would fall before its start is lifted
 * to it, so the range never reads backwards.
 */
private fun workBlockLabel(
    minStartMinutes: Int,
    maxEndMinutes: Int,
): String {
    val start = minStartMinutes.coerceIn(0, MINUTES_IN_DAY - 1)
    val end = maxEndMinutes.coerceIn(start, MINUTES_IN_DAY - 1)
    return "Work block: ${clockOf(start)}–${clockOf(end)}"
}

/** A minute of the day as a clock: `540` → "09:00". */
private fun clockOf(minute: Int): String = "${formatPadded(minute / 60)}:${formatPadded(minute % 60)}"

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
