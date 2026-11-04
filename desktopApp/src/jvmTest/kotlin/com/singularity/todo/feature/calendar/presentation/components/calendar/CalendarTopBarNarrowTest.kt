@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.calendar.presentation.components.calendar

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.domain.state.CalendarUiState
import com.singularity.todo.feature.calendar.presentation.theme.ProvideCalendarPalette
import com.singularity.todo.test.helpers.assertContentDescriptionReachable
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.scrollToTag
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The calendar's bar at a phone width.
 *
 * The bar is one row: the period name, then the view-mode trigger, the
 * mini-calendar toggle, the month pager and "Today". On a 360 dp window those
 * children do not fit, and Compose gives the deficit to the last child — which
 * used to be the "Today" label, rendered as a column of single letters about
 * 165 px tall. These tests say what a bar may do when it runs out of room: a
 * text truncates on one line, and no control is squeezed out of reach.
 *
 * The period name wins the contest for room. A name longer than the whole bar
 * ("September 28 – October 4, 2026") leaves the control strip nothing to show
 * until the window is widened — the strip scrolls within whatever it is given,
 * and this file does not claim otherwise.
 */
@Tag("fast")
class CalendarTopBarNarrowTest {
    private val today = LocalDate(2026, 10, 12)

    private fun barState(
        headerLabel: String,
        mode: CalendarViewMode = CalendarViewMode.MONTH,
    ) = CalendarUiState.Loaded(
        anchor = LocalDate(2026, 9, 1),
        viewMode = mode,
        visibleDates = persistentListOf(today),
        tasksByDate = persistentMapOf(),
        today = today,
        selectedDate = today,
        headerLabel = headerLabel,
    )

    @Test
    fun `the today control stays one line at a phone width`() =
        runIsolatedComposeTest(width = 360, height = 240) {
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        CalendarTopBar(
                            state = barState("September 2026", CalendarViewMode.FOUR_DAYS),
                            today = today,
                            onIntent = {},
                        )
                    }
                }
            }

            scrollToTag(TestTags.CALENDAR_TODAY)
            assertTagDisplayed(TestTags.CALENDAR_TODAY)

            val control = unclippedBoundsOfTag(TestTags.CALENDAR_TODAY)
            // The node is the touch target, so one line is ~48 dp tall; the
            // wrapped label the bar used to draw was more than twice that.
            assertTrue(
                control.height <= 52.dp,
                "the Today control wrapped: it is ${control.height} tall at a 360 dp width",
            )
            assertTrue(
                control.width >= 24.dp,
                "the Today control was squeezed to ${control.width}",
            )
        }

    @Test
    fun `every control of the bar is reachable at a phone width`() =
        runIsolatedComposeTest(width = 360, height = 240) {
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        CalendarTopBar(
                            state = barState("September 2026", CalendarViewMode.FOUR_DAYS),
                            today = today,
                            onIntent = {},
                        )
                    }
                }
            }

            assertTagDisplayed(TestTags.CALENDAR_VIEW_MODE_TRIGGER)
            assertContentDescriptionReachable("Open mini calendar")
            assertContentDescriptionReachable("Previous")
            assertContentDescriptionReachable("Next")
        }

    @Test
    fun `the period name truncates instead of wrapping when it is longer than the bar`() =
        runIsolatedComposeTest(width = 360, height = 240) {
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        CalendarTopBar(
                            state = barState("September 28 – October 4, 2026", CalendarViewMode.FOUR_DAYS),
                            today = today,
                            onIntent = {},
                        )
                    }
                }
            }

            val title = unclippedBoundsOfTag(TestTags.CALENDAR_MONTH_TITLE)
            assertTrue(
                title.height <= 32.dp,
                "the period name wrapped: it is ${title.height} tall at a 360 dp width",
            )
            assertTrue(
                title.right - title.left >= 100.dp,
                "the period name collapsed to ${title.right - title.left}: it is the bar's title",
            )
        }

    @Test
    fun `the controls keep the bar's right edge on a wide window`() =
        runIsolatedComposeTest {
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        CalendarTopBar(
                            state = barState("September 2026"),
                            today = today,
                            onIntent = {},
                        )
                    }
                }
            }

            val strip = unclippedBoundsOfTag(TestTags.CALENDAR_CONTROLS)
            assertTrue(
                strip.right.value >= 1000f,
                "the control strip no longer ends at the bar's right edge: ${strip.right}",
            )
            assertTrue(
                strip.right - strip.left <= 420.dp,
                "the control strip grew to fill the bar instead of hugging its controls: " +
                    "${strip.right - strip.left}",
            )
        }
}
