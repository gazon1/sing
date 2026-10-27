package com.singularity.todo.feature.plans.presentation.viewmodel

import com.singularity.todo.feature.plans.domain.model.PlansGranularity
import com.singularity.todo.feature.plans.domain.model.TimelineSection
import kotlinx.collections.immutable.ImmutableList
import kotlinx.datetime.LocalDate

/**
 * The state of the plan screen.
 *
 * @param sections The evaluated sections, oldest period first, with the overdue
 *        section first when there is one.
 * @param granularity The granularity the sections were built at.
 * @param today The date the sections were built against.
 */
sealed interface PlansUiState {
    data object Loading : PlansUiState

    data class Loaded(
        val sections: ImmutableList<TimelineSection>,
        val granularity: PlansGranularity,
        val today: LocalDate,
    ) : PlansUiState {
        /** Nothing is scheduled inside the horizon. */
        val isEmpty: Boolean get() = sections.isEmpty()
    }

    data class Error(val message: String) : PlansUiState
}
