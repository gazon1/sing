package com.singularity.todo.feature.plans.domain.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.datetime.LocalDate

/**
 * A period, its label, and the tasks that belong to it.
 *
 * @param id The period's id — the section's key.
 * @param label What the header says: `Today`, `Tomorrow`, `Fri, 12 Sep`,
 *   `12–18 Sep`, `September 2026`.
 * @param period The span the section covers; null for the overdue section, which
 *   belongs to no future day.
 * @param tasks The open tasks due inside the period, in the order they are shown.
 * @param prefillDate The date a task added from this section carries; null for
 *   the overdue section, which has no date to prefill.
 */
data class TimelineSection(
    val id: String,
    val label: String,
    val period: TimelinePeriod?,
    val tasks: ImmutableList<TimelineTask>,
    val prefillDate: LocalDate? = null,
)
