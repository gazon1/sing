package com.singularity.todo.feature.agenda.domain.selector

import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus

/**
 * Human-readable description of a [Selector] for UI display.
 *
 * This is the **single source of truth** for selector descriptions — never duplicate
 * the description logic elsewhere.
 *
 * ## Coverage
 *
 * All 13 Selector variants are covered: DateBucket, DateRange, Statuses,
 * Priorities, Tags, Projects, Pinned, Completed, Overdue, Regexp,
 * AllOf, AnyOf, Not, Anything.
 *
 * @see Selector
 */
val Selector.typeDescription: String
    get() = when (this) {
        is Selector.DateBucket -> when (bucket) {
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

        is Selector.DateRange -> "Date range: $from – $to"

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

        is Selector.Tags -> when {
            matchAll -> "All tags: ${ids.size}"
            else -> "Any tag: ${ids.size}"
        }

        is Selector.Projects -> when (ids.size) {
            0 -> "No project"
            1 -> "Project"
            else -> "${ids.size} projects"
        }

        is Selector.Pinned -> "Pinned"
        is Selector.Completed -> "Completed"
        is Selector.Overdue -> "Overdue"
        is Selector.Regexp -> "Regex: $query"
        is Selector.AllOf -> "All of (${children.size} rules)"
        is Selector.AnyOf -> "Any of (${children.size} rules)"
        is Selector.Not -> "Not: ${child.typeDescription}"
        is Selector.Anything -> "All tasks"
    }

/** Name for [TaskStatus] display. */
private val TaskStatus.statusName: String
    get() = when (this) {
        TaskStatus.Active -> "Active"
        TaskStatus.Completed -> "Completed"
        TaskStatus.All -> "All"
    }

/** Name for [TaskPriority] display. */
private val TaskPriority.priorityName: String
    get() = when (this) {
        TaskPriority.High -> "High"
        TaskPriority.Medium -> "Medium"
        TaskPriority.Low -> "Low"
        TaskPriority.None -> "None"
        TaskPriority.Urgent -> "Urgent"
    }
