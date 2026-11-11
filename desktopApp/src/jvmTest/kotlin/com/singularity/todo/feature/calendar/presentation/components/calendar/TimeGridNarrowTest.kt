@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.calendar.presentation.components.calendar

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.calendar.domain.model.CalendarTaskUi
import com.singularity.todo.feature.calendar.presentation.theme.ProvideCalendarPalette
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.assertTextNotExists
import com.singularity.todo.test.helpers.clickTagFirst
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.countNodes
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unclippedBoundsOfTagAt
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The week grid's all-day strip at phone and tablet widths.
 *
 * The bug report is from a 360 dp window: `(360 − 56) / 7 ≈ 43.4 dp` per day
 * column, where a title chip renders a two-or-three-character fragment and the
 * weekday label wrapped one letter per line. The strip now switches to a compact
 * marker below [MIN_TITLE_CHIP_DAY_WIDTH] and keeps title chips above it.
 *
 * These are measurements, not estimates: the day-column width comes from the
 * laid-out tree, and the marker's bounds are compared against the column so a
 * reserved box that spills into the next day fails here.
 */
@Tag("slow")
@Tag("slow:compose")
class TimeGridNarrowTest {
    private val monday = LocalDate(2026, 10, 12)
    private val week: ImmutableList<LocalDate> =
        (0..6).map { monday.plus(it, DateTimeUnit.DAY) }.toImmutableList()

    private fun tasksFor(
        dates: List<LocalDate> = week,
        perDay: Int = 5,
    ): ImmutableMap<LocalDate, ImmutableList<CalendarTaskUi>> =
        dates
            .associateWith { date ->
                (1..perDay)
                    .map { n ->
                        CalendarTaskUi(
                            id = TaskId("task-$date-$n"),
                            title = "Событие $n дня ${date.day}",
                            date = date,
                            isAllDay = true,
                        )
                    }.toImmutableList()
            }.toImmutableMap()

    private fun weekDayWidth(bounds: androidx.compose.ui.unit.DpRect) = bounds.right - bounds.left

    @Test
    fun `a 360 dp week measures about 43 dp per day and uses compact markers`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        TimeGridView(
                            dates = week,
                            tasksByDate = tasksFor(),
                            today = monday,
                        )
                    }
                }
            }

            val cellWidth = weekDayWidth(unclippedBoundsOfTagAt(TestTags.CALENDAR_DAY_CELL, index = 0))
            assertEquals(43.4f, cellWidth.value, 1.5f, "measured week-column width at 360 dp")

            assertTrue(
                countNodes(TestTags.CALENDAR_ALL_DAY_MARKER, useUnmergedTree = true) > 0,
                "a 43 dp column should render compact markers",
            )
            assertTextNotExists("Событие 1 дня 12")
        }

    @Test
    fun `a 600 dp week keeps title chips`() =
        runIsolatedComposeTest(width = 600, height = 800) {
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        TimeGridView(
                            dates = week,
                            tasksByDate = tasksFor(),
                            today = monday,
                        )
                    }
                }
            }

            val cellWidth = weekDayWidth(unclippedBoundsOfTagAt(TestTags.CALENDAR_DAY_CELL, index = 0))
            assertEquals(77.7f, cellWidth.value, 1.5f, "measured week-column width at 600 dp")

            assertEquals(
                0,
                countNodes(TestTags.CALENDAR_ALL_DAY_MARKER, useUnmergedTree = true),
                "a 77.7 dp column should keep title chips, not markers",
            )
            assertTextDisplayed("Событие 1 дня 12")
        }

    @Test
    fun `a 360 dp four-day column keeps title chips`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val fourDays = week.take(4)
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        TimeGridView(
                            dates = fourDays.toImmutableList(),
                            tasksByDate = tasksFor(fourDays),
                            today = monday,
                        )
                    }
                }
            }

            val cellWidth = weekDayWidth(unclippedBoundsOfTagAt(TestTags.CALENDAR_DAY_CELL, index = 0))
            assertEquals(76f, cellWidth.value, 1.5f, "measured four-day column at 360 dp")
            assertEquals(0, countNodes(TestTags.CALENDAR_ALL_DAY_MARKER, useUnmergedTree = true))
            assertTextDisplayed("Событие 1 дня 12")
        }

    @Test
    fun `empty and single-event days render one marker and no overflow`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        TimeGridView(
                            dates = week,
                            tasksByDate =
                                persistentMapOf(
                                    week[0] to
                                        persistentListOf(
                                            CalendarTaskUi(
                                                id = TaskId("only"),
                                                title = "Единственное событие",
                                                date = week[0],
                                                isAllDay = true,
                                            ),
                                        ),
                                ),
                            today = monday,
                        )
                    }
                }
            }

            assertEquals(
                1,
                countNodes(TestTags.CALENDAR_ALL_DAY_MARKER, useUnmergedTree = true),
                "one event on Monday is one marker; the other six days are empty",
            )
            assertTextNotExists("+1")
            assertTextNotExists("Единственное событие")
        }

    @Test
    fun `a column just below the breakpoint is compact`() =
        runIsolatedComposeTest(width = 540, height = 800) {
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        TimeGridView(dates = week, tasksByDate = tasksFor(), today = monday)
                    }
                }
            }
            // 69.1 dp per day.
            assertTrue(countNodes(TestTags.CALENDAR_ALL_DAY_MARKER, useUnmergedTree = true) > 0)
        }

    @Test
    fun `a column just above the breakpoint is a title chip`() =
        runIsolatedComposeTest(width = 570, height = 800) {
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        TimeGridView(dates = week, tasksByDate = tasksFor(), today = monday)
                    }
                }
            }
            // 73.4 dp per day.
            assertEquals(0, countNodes(TestTags.CALENDAR_ALL_DAY_MARKER, useUnmergedTree = true))
        }

    @Test
    fun `a compact marker stays inside its own day column`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        TimeGridView(dates = week, tasksByDate = tasksFor(), today = monday)
                    }
                }
            }

            val cell = unclippedBoundsOfTagAt(TestTags.CALENDAR_DAY_CELL, index = 0)
            val marker =
                unclippedBoundsOfTagAt(
                    TestTags.CALENDAR_ALL_DAY_MARKER,
                    index = 0,
                    useUnmergedTree = true,
                )
            val cellWidth = weekDayWidth(cell)

            assertTrue(
                weekDayWidth(marker) <= cellWidth + 0.5.dp,
                "the marker reserved ${weekDayWidth(marker)} in a $cellWidth column — it spills into the next day",
            )
            assertTrue(
                marker.left >= cell.left - 0.5.dp && marker.right <= cell.right + 0.5.dp,
                "the marker is positioned outside its day column: [$marker] in [$cell]",
            )
        }

    @Test
    fun `tapping a date header opens that date`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            var opened: LocalDate? = null
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        TimeGridView(
                            dates = week,
                            tasksByDate = tasksFor(),
                            today = monday,
                            onDayClick = { opened = it },
                        )
                    }
                }
            }

            clickTagFirst(TestTags.CALENDAR_DAY_CELL)

            assertEquals(week[0], opened, "the date header opens its own date")
        }

    @Test
    fun `tapping a narrow marker opens its task, not the day`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            var tapped: CalendarTaskUi? = null
            var opened: LocalDate? = null
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        TimeGridView(
                            dates = week,
                            tasksByDate = tasksFor(),
                            today = monday,
                            onTaskClick = { tapped = it },
                            onDayClick = { opened = it },
                        )
                    }
                }
            }

            clickTagFirst(TestTags.CALENDAR_ALL_DAY_MARKER)

            assertEquals("Событие 1 дня 12", tapped?.title, "the marker opens its own task")
            assertNull(opened, "a marker click is not the day action")
        }

    @Test
    fun `tapping a wide title chip opens its task, not the day`() =
        runIsolatedComposeTest(width = 600, height = 800) {
            var tapped: CalendarTaskUi? = null
            var opened: LocalDate? = null
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        TimeGridView(
                            dates = week,
                            tasksByDate = tasksFor(),
                            today = monday,
                            onTaskClick = { tapped = it },
                            onDayClick = { opened = it },
                        )
                    }
                }
            }

            clickText("Событие 1 дня 12")

            assertEquals("Событие 1 дня 12", tapped?.title)
            assertNull(opened)
        }

    @Test
    fun `the collapsed overflow opens the day it belongs to`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            var opened: LocalDate? = null
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        TimeGridView(
                            dates = week,
                            tasksByDate = tasksFor(),
                            today = monday,
                            onDayClick = { opened = it },
                        )
                    }
                }
            }

            // Five tasks a day, two shown in a narrow column: every column has "+3".
            onAllNodesWithText("+3").onFirst().performClick()

            assertEquals(week[0], opened, "the overflow label opens its own date")
        }

    @Test
    fun `the compact header stays one line`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        TimeGridView(dates = week, tasksByDate = tasksFor(), today = monday)
                    }
                }
            }

            val header = unclippedBoundsOfTagAt(TestTags.CALENDAR_DAY_CELL, index = 0)
            val height = header.bottom - header.top
            assertTrue(
                height <= 60.dp,
                "the compact header wrapped: it is $height tall at 43 dp per day",
            )
            assertTextDisplayed("Mon")
        }

    @Test
    fun `a busy single day is capped and scrolls instead of clipping`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        TimeGridView(
                            dates = persistentListOf(monday),
                            tasksByDate = tasksFor(listOf(monday), perDay = 30),
                            today = monday,
                        )
                    }
                }
            }

            val cell = unclippedBoundsOfTagAt(TestTags.CALENDAR_ALL_DAY_CELL, index = 0)
            val height = cell.bottom - cell.top
            assertTrue(
                height <= 161.dp,
                "the single-day strip grew to $height: it should be capped and scroll",
            )
        }

    @Test
    fun `the single-day view renders every all-day task`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        TimeGridView(
                            dates = persistentListOf(monday),
                            tasksByDate = tasksFor(listOf(monday)),
                            today = monday,
                        )
                    }
                }
            }

            // The week grid collapsed tasks 4 and 5; opening the day must not.
            assertTextDisplayed("Событие 4 дня 12")
            assertTextDisplayed("Событие 5 дня 12")
            assertTextNotExists("+2")
        }
}
