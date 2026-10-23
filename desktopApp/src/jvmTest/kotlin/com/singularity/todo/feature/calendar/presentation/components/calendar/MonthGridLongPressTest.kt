@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.calendar.presentation.components.calendar

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.calendar.domain.model.CalendarTaskUi
import com.singularity.todo.feature.calendar.presentation.theme.ProvideCalendarPalette
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.helpers.longClickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/**
 * The long press on a calendar cell.
 *
 * The gesture answers two different questions, and which one it is depends on
 * the day: a day that already holds tasks enters the date-selection mode, while
 * an empty day keeps its old meaning of "create one task on this day". Before
 * the series feature the long press was wired only on empty cells, so the
 * gesture that starts a multi-selection could not be performed on the days a
 * user is most likely to pick. These tests pin both answers.
 *
 * The days used here are in the middle of the month on purpose: a month grid
 * is six weeks long and shows the days of the neighbouring months at its
 * edges, so the first days of a month are drawn twice — once in that month and
 * once as the trailing days of the month before. A day in the middle is drawn
 * once, and the test presses what it means to press.
 */
@Tag("fast")
class MonthGridLongPressTest {

    private val anchor = LocalDate(2026, 10, 1)
    private val busyDay = LocalDate(2026, 10, 20)
    private val emptyDay = LocalDate(2026, 10, 21)

    private fun tasksByDate(): ImmutableMap<LocalDate, ImmutableList<CalendarTaskUi>> = persistentMapOf(
        busyDay to persistentListOf(
            CalendarTaskUi(
                id = TaskId("task-1"),
                title = "Pay the rent",
                date = busyDay,
            ),
        ),
    )

    @Test
    fun `a long press on a day with tasks enters the selection mode`() = runIsolatedComposeTest {
        var selectionStarted: LocalDate? = null
        var emptyCellPressed: LocalDate? = null

        setContent {
            ProvideCalendarPalette {
                MaterialTheme {
                    MonthGridView(
                        monthAnchor = anchor,
                        today = anchor,
                        selectedDate = anchor,
                        tasksByDate = tasksByDate(),
                        onDayClick = {},
                        onTaskClick = {},
                        onMonthPageChanged = {},
                        onEmptyCellLongPress = { emptyCellPressed = it },
                        onDayLongPress = { selectionStarted = it },
                        modifier = Modifier.size(420.dp, 600.dp),
                    )
                }
            }
        }

        longClickTag(TestTags.calendarDay("2026-10-20"))

        assertEquals(busyDay, selectionStarted, "a day with tasks starts the selection")
        assertNull(emptyCellPressed, "a day with tasks is not an empty cell")
    }

    @Test
    fun `a long press on an empty day keeps its old meaning`() = runIsolatedComposeTest {
        var selectionStarted: LocalDate? = null
        var emptyCellPressed: LocalDate? = null

        setContent {
            ProvideCalendarPalette {
                MaterialTheme {
                    MonthGridView(
                        monthAnchor = anchor,
                        today = anchor,
                        selectedDate = anchor,
                        tasksByDate = tasksByDate(),
                        onDayClick = {},
                        onTaskClick = {},
                        onMonthPageChanged = {},
                        onEmptyCellLongPress = { emptyCellPressed = it },
                        onDayLongPress = { selectionStarted = it },
                        modifier = Modifier.size(420.dp, 600.dp),
                    )
                }
            }
        }

        longClickTag(TestTags.calendarDay("2026-10-21"))

        assertEquals(emptyDay, emptyCellPressed, "an empty day still means one task on that day")
        assertNull(selectionStarted, "an empty day does not start a selection")
    }
}
