package com.singularity.todo.feature.agenda.domain.model

import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Relative date buckets for [DateBucket] selector.
 *
 * Used for preset sections like "Today", "This Week", "Overdue", etc.
 * The actual date range for each bucket is computed by [RelativeBucket.toDateRange]
 * using the provided `today`.
 */
@Serializable
enum class RelativeBucket {
    Today,
    Yesterday,
    Tomorrow,
    ThisWeek,
    NextWeek,
    ThisMonth,
    NextMonth,
    Overdue,
    NoDate,
}

/**
 * A selector predicate that filters tasks for a section.
 *
 * All selectors are pure predicates — they carry no UI metadata (badges, colors, etc.).
 * Metadata (e.g. "12 tasks overdue") is computed from the result by [AgendaEvaluator].
 *
 * @see AgendaEvaluator.matches
 */
@Serializable(with = SelectorSerializer::class)
@SerialName("Selector")
sealed interface Selector {

    /**
     * Tasks bucketed by a relative date period.
     * - [RelativeBucket.Overdue] — dueDate < today
     * - [RelativeBucket.NoDate] — dueDate is null
     * All other buckets use the range computed by [RelativeBucket.toDateRange].
     */
    @Serializable
    @SerialName("DateBucket")
    data class DateBucket(val bucket: RelativeBucket) : Selector

    /**
     * Tasks with dueDate in the inclusive range [from]..[to].
     * Used by the Calendar screen and custom date-range presets.
     */
    @Serializable
    @SerialName("DateRange")
    data class DateRange(val from: kotlinx.datetime.LocalDate, val to: kotlinx.datetime.LocalDate) : Selector

    /** Tasks filtered by completion status. */
    @Serializable
    @SerialName("Statuses")
    data class Statuses(val statuses: Set<TaskStatus>) : Selector

    /**
     * Tasks filtered by priority.
     * @param atMost If true (default), matches tasks with priority IN [priorities].
     *               If false, matches tasks with priority NOT in [priorities].
     */
    @Serializable
    @SerialName("Priorities")
    data class Priorities(val priorities: Set<TaskPriority>, val atMost: Boolean = true) : Selector

    /**
     * Tasks that are tagged with any of the given [TagId]s.
     *
     * @param ids The set of tag IDs to match against.
     * @param matchAll When true (default false in DSL), a task must have all of the
     *                 given tags. When false, a task matching any one tag is included.
     */
    @Serializable
    @SerialName("Tags")
    data class Tags(val ids: Set<TagId>, val matchAll: Boolean = false) : Selector

    /** Tasks that belong to any of the given projects. */
    @Serializable
    @SerialName("Projects")
    data class Projects(val ids: Set<ProjectId>) : Selector

    /** Pinned (starred) tasks. */
    @Serializable
    @SerialName("Pinned")
    data object Pinned : Selector

    /** Completed tasks (completedAt != null). */
    @Serializable
    @SerialName("Completed")
    data object Completed : Selector

    /**
     * Overdue tasks — dueDate < today AND not completed.
     * A shortcut for `DateBucket(Overdue)` combined with `Statuses(setOf(Active))`.
     */
    @Serializable
    @SerialName("Overdue")
    data object Overdue : Selector

    /** Tasks whose title matches the given regular expression (case-insensitive). */
    @Serializable
    @SerialName("Regexp")
    data class Regexp(val query: String) : Selector

    /** All children must match. */
    @Serializable
    @SerialName("AllOf")
    data class AllOf(val children: List<Selector>) : Selector

    /** At least one child must match. */
    @Serializable
    @SerialName("AnyOf")
    data class AnyOf(val children: List<Selector>) : Selector

    /** The child must NOT match. */
    @Serializable
    @SerialName("Not")
    data class Not(val child: Selector) : Selector

    /** Matches every task (used as base filter). */
    @Serializable
    @SerialName("Anything")
    data object Anything : Selector
}
