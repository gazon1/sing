package com.singularity.todo.feature.calendar.presentation.state
import androidx.compose.runtime.Immutable

import com.singularity.todo.feature.calendar.domain.model.CalendarTaskUi
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import kotlinx.datetime.LocalDate

/**
 * UI state for the Calendar screen.
 */
sealed interface CalendarUiState {
    /** Initial load. */
    data object Loading : CalendarUiState

    /**
     * Main loaded state.
     *
     * @param anchor The reference date (1st day of the displayed month).
     * @param viewMode Current view mode (Day / 4 days / Week / Month).
     * @param visibleDates Dates visible in the current view (computed from [anchor] + [viewMode]).
     * @param tasksByDate Tasks grouped by date, covering the visible window ± margin.
     * @param today Today's date (used to highlight the "now" line and today badge).
     * @param selectedDate The date the user has selected (clicked in the grid).
     * @param isMiniCalendarOpen Whether the right-side mini calendar panel is visible.
     * @param headerLabel Human-readable label for the current view range.
     * @param selectedTaskId The task currently highlighted (if any).
     */
    data class Loaded(
        val anchor: LocalDate,
        val viewMode: CalendarViewMode,
        val visibleDates: List<LocalDate>,
        val tasksByDate: Map<LocalDate, List<CalendarTaskUi>>,
        val today: LocalDate,
        val selectedDate: LocalDate,
        val isMiniCalendarOpen: Boolean = false,
        val headerLabel: String,
        val selectedTaskId: String? = null,
    ) : CalendarUiState
}
