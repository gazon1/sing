package com.singularity.todo.core.ui.components

import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.domain.state.CalendarUiState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for the screen-state projections in [ContentStateMapper].
 *
 * The calendar used to hand-roll its projection inside the screen, so nothing stated
 * what a [CalendarUiState] variant renders as and a new variant would have been
 * caught by reading rather than by a test. The projection lives here for the same
 * reason the notes and tags ones do: one place, and a test that fails when a variant
 * changes what the screen shows.
 */
@Tag("fast")
class ContentStateMapperTest {
    @Test
    fun `the first calendar load renders as loading`() {
        assertEquals(ContentState.Loading, ContentStateMapper.calendar(CalendarUiState.Loading))
    }

    @Test
    fun `a loaded calendar renders as ready and keeps the month`() {
        val loaded = loadedMonth()

        val ready = assertIs<ContentState.Ready<CalendarUiState.Loaded>>(ContentStateMapper.calendar(loaded))

        assertEquals(loaded, ready.value)
    }

    private fun loadedMonth() =
        CalendarUiState.Loaded(
            anchor = LocalDate(2026, 9, 1),
            viewMode = CalendarViewMode.MONTH,
            visibleDates = persistentListOf(LocalDate(2026, 9, 1)),
            tasksByDate = persistentMapOf(),
            today = LocalDate(2026, 9, 16),
            selectedDate = LocalDate(2026, 9, 1),
            headerLabel = "September 2026",
        )
}
