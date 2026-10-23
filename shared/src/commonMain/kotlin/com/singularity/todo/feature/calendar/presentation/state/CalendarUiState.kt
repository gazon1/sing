package com.singularity.todo.feature.calendar.presentation.state
import com.singularity.todo.feature.calendar.domain.model.CalendarTaskUi
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.datetime.LocalDate

/**
 * UI state for the Calendar screen.
 *
 * ## Why the collections are persistent, and why there is no `@Immutable`
 *
 * [ImmutableList] / [ImmutableMap] / [ImmutableSet] are known-stable
 * constructs for the Compose compiler: a composable that takes one compares
 * it by `equals` instead of by identity, and the calendar's day cells and task
 * chips skip themselves when their day did not change.
 *
 * The screen state itself carries no `@Immutable`: that would make the
 * compiler compare the whole month by `equals` on every observation of the
 * state. Identity is the cheap and honest comparison at screen level; the
 * leaves carry the stability. See docs/architecture/compose-stability.md.
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
        val visibleDates: ImmutableList<LocalDate>,
        val tasksByDate: ImmutableMap<LocalDate, ImmutableList<CalendarTaskUi>>,
        val today: LocalDate,
        val selectedDate: LocalDate,
        val isMiniCalendarOpen: Boolean = false,
        val headerLabel: String,
        val selectedTaskId: String? = null,
        /**
         * The days picked for one task, when the selection mode is on.
         *
         * Empty outside the mode. The set survives paging to another month: the
         * user picked days, and paging away must not throw the picking away.
         */
        val selectedDates: ImmutableSet<LocalDate> = persistentSetOf(),
        /**
         * Whether the screen is in the date-selection mode.
         *
         * In the mode a tap toggles a day instead of opening it, and the panel
         * at the bottom offers to create one task on the picked days.
         */
        val isDateSelectionMode: Boolean = false,
    ) : CalendarUiState
}
