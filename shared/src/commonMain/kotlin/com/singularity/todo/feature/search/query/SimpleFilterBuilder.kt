package com.singularity.todo.feature.search.query

import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlinx.datetime.LocalDate

/**
 * Builder DSL for [SimpleFilter], inspired by the AgendaEngine DSL pattern.
 *
 * Example usage:
 * ```
 * val filter = simpleFilter {
 *     state(TaskStatus.Active)
 *     priority(TaskPriority.High)
 *     tag("work")
 *     tag("urgent", matchAll = true)
 *     due(SimpleFilter.DueCondition.TODAY)
 *     sortOrder(SortOrder.PRIORITY)
 *     sortDescending(true)
 *     freeText("meeting")
 * }
 * ```
 *
 * @see simpleFilter
 */
@DslMarker
annotation class SimpleFilterDsl

@SimpleFilterDsl
class SimpleFilterBuilder {
    private var states: Set<TaskStatus>? = null
    private var priorities: Set<TaskPriority> = emptySet()
    private var tagNames: MutableSet<String> = mutableSetOf()
    private var matchAllTags: Boolean = false
    private var projectName: String? = null
    private var due: SimpleFilter.DueCondition = SimpleFilter.DueCondition.NONE
    private var customDueDate: LocalDate? = null
    private var customDueDateEnd: LocalDate? = null
    private var hasDescription: Boolean? = null
    private var pinned: Boolean? = null
    private var sortOrder: SortOrder = SortOrder.DUE
    private var sortDescending: Boolean = false
    private var freeTextValue: String? = null

    /**
     * Sets free-text search terms appended to the query.
     */
    fun freeText(value: String?) {
        freeTextValue = value
    }

    /**
     * Sets the state filter.
     * Calling `state()` overrides any previous state setting.
     * Subsequent calls accumulate states (OR semantics).
     */
    fun state(status: TaskStatus) {
        states = (states ?: emptySet()) + status
    }

    fun priority(priority: TaskPriority) {
        priorities = priorities + priority
    }

    /**
     * Adds a tag to the filter.
     *
     * @param name The tag name (exact match)
     * @param matchAll When true, all added tags use matchAll semantics.
     *                 Defaults to false (OR semantics between tags).
     */
    fun tag(name: String, matchAll: Boolean = false) {
        tagNames.add(name)
        if (matchAll) matchAllTags = true
    }

    fun project(name: String?) {
        projectName = name
    }

    fun due(condition: SimpleFilter.DueCondition) {
        due = condition
    }

    fun customDueRange(from: LocalDate, to: LocalDate) {
        due = SimpleFilter.DueCondition.CUSTOM
        customDueDate = from
        customDueDateEnd = to
    }

    fun hasDescription(value: Boolean) {
        hasDescription = value
    }

    fun pinned(value: Boolean) {
        pinned = value
    }

    fun sortOrder(order: SortOrder) {
        sortOrder = order
    }

    fun sortDescending(value: Boolean) {
        sortDescending = value
    }

    fun build(): SimpleFilter = SimpleFilter(
        states = states,
        priorities = priorities,
        tagNames = tagNames.toSet(),
        matchAllTags = matchAllTags,
        projectName = projectName,
        due = due,
        customDueDate = customDueDate,
        customDueDateEnd = customDueDateEnd,
        hasDescription = hasDescription,
        pinned = pinned,
        sortOrder = sortOrder,
        sortDescending = sortDescending,
        freeText = freeTextValue,
    )
}

/**
 * Creates a [SimpleFilter] using the builder DSL.
 *
 * Example:
 * ```
 * val filter = simpleFilter {
 *     state(TaskStatus.Active)
 *     priority(TaskPriority.Urgent)
 *     tag("work")
 *     due(SimpleFilter.DueCondition.TODAY)
 * }
 * ```
 */
fun simpleFilter(block: SimpleFilterBuilder.() -> Unit): SimpleFilter = SimpleFilterBuilder().apply(block).build()
