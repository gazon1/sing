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
import com.singularity.todo.test.helpers.countNodesWithText
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.touchBoundsOfTagInUnmergedTree
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
                            onDayClick = {},
                        )
                    }
                }
            }

            val cellWidth = weekDayWidth(unclippedBoundsOfTagAt(TestTags.CALENDAR_DAY_CELL, index = 0))
            assertEquals(43.4f, cellWidth.value, 1.5f, "measured week-column width at 360 dp")

            assertEquals(
                14,
                countNodes(TestTags.CALENDAR_ALL_DAY_MARKER, useUnmergedTree = true),
                "two markers per day, seven days",
            )
            assertEquals(7, countNodesWithText("+3"), "five tasks, two shown: one +3 per day")
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
                            onDayClick = {},
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
            assertEquals(7, countNodesWithText("+2 more"), "five tasks, three chips: one +2 per day")
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
                            onDayClick = {},
                        )
                    }
                }
            }

            val cellWidth = weekDayWidth(unclippedBoundsOfTagAt(TestTags.CALENDAR_DAY_CELL, index = 0))
            assertEquals(76f, cellWidth.value, 1.5f, "measured four-day column at 360 dp")
            assertEquals(0, countNodes(TestTags.CALENDAR_ALL_DAY_MARKER, useUnmergedTree = true))
            assertEquals(4, countNodesWithText("+2 more"), "five tasks, three chips: one +2 per day")
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
                            onDayClick = {},
                        )
                    }
                }
            }

            assertEquals(
                1,
                countNodes(TestTags.CALENDAR_ALL_DAY_MARKER, useUnmergedTree = true),
                "one event on Monday is one marker; the other six days are empty",
            )
            assertEquals(0, countNodesWithText("+1"), "one event has nothing to overflow")
            assertTextNotExists("Единственное событие")
        }

    @Test
    fun `a multi-day grid without a day action still caps the column`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            // The cap comes from the view mode, not from whether a callback was
            // supplied: omitting onDayClick must not turn a week into a day view.
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        TimeGridView(dates = week, tasksByDate = tasksFor(), today = monday)
                    }
                }
            }

            assertEquals(14, countNodes(TestTags.CALENDAR_ALL_DAY_MARKER, useUnmergedTree = true))
            assertEquals(7, countNodesWithText("+3"))
            assertTextNotExists("Событие 1 дня 12")
        }

    @Test
    fun `a column just below the breakpoint is compact`() =
        runIsolatedComposeTest(width = 540, height = 800) {
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        TimeGridView(
                            dates = week,
                            tasksByDate = tasksFor(),
                            today = monday,
                            onDayClick = {},
                        )
                    }
                }
            }
            // 69.1 dp per day.
            assertEquals(14, countNodes(TestTags.CALENDAR_ALL_DAY_MARKER, useUnmergedTree = true))
        }

    @Test
    fun `a column just above the breakpoint is a title chip`() =
        runIsolatedComposeTest(width = 570, height = 800) {
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        TimeGridView(
                            dates = week,
                            tasksByDate = tasksFor(),
                            today = monday,
                            onDayClick = {},
                        )
                    }
                }
            }
            // 73.4 dp per day.
            assertEquals(0, countNodes(TestTags.CALENDAR_ALL_DAY_MARKER, useUnmergedTree = true))
        }

    @Test
    fun `a compact marker is 48 dp tall, as wide as its column, and stays inside it`() =
        runIsolatedComposeTest(width = 360, height = 800) {
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        TimeGridView(
                            dates = week,
                            tasksByDate = tasksFor(),
                            today = monday,
                            onDayClick = {},
                        )
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
            val markerHeight = marker.bottom - marker.top

            // The layout target is the column's width by the 48 dp minimum. It is
            // not 48 x 48: a 48 dp-wide slot would overlap the neighbouring day.
            // Compose expands a clickable's pointer bounds to the platform
            // minimum, centred; the semantics bounds stay the laid-out size.
            assertTrue(
                markerHeight >= 48.dp,
                "the marker is $markerHeight tall, below the 48 dp minimum",
            )
            val touch = touchBoundsOfTagInUnmergedTree(TestTags.CALENDAR_ALL_DAY_MARKER)
            val touchWidth = touch.right - touch.left
            assertTrue(
                touchWidth >= 48.dp,
                "the pointer target is $touchWidth wide; Compose should expand it to the 48 dp minimum",
            )
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
                        TimeGridView(
                            dates = week,
                            tasksByDate = tasksFor(),
                            today = monday,
                            onDayClick = {},
                        )
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
