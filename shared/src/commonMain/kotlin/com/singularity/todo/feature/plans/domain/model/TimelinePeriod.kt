package com.singularity.todo.feature.plans.domain.model

import kotlinx.datetime.LocalDate

/**
 * One span of time a timeline section covers.
 *
 * The periods of one granularity are pairwise disjoint and sorted by [start].
 * That is the property that makes "a task is in exactly one section" structural
 * rather than a rule to remember: a due date can fall inside at most one of
 * them.
 *
 * @param id Stable key, unique within a timeline — `day:2026-10-26`,
 *   `week:2026-10-20`, `month:2026-11`.
 * @param start First day of the period, inclusive.
 * @param end Last day of the period, inclusive.
 */
data class TimelinePeriod(val id: String, val start: LocalDate, val end: LocalDate) {
    /** Whether [date] falls inside this period. */
    fun contains(date: LocalDate): Boolean = date >= start && date <= end
}
