@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.calendar.presentation.components.calendar

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.domain.state.CalendarUiState
import com.singularity.todo.feature.calendar.presentation.theme.ProvideCalendarPalette
import com.singularity.todo.test.helpers.assertContentDescriptionDisplayed
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.runIsolatedComposeTest
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
 * The bar is the period name plus the controls: the view-mode trigger, the
 * mini-calendar toggle, the month pager and "Today". On a 360 dp window they do
 * not fit on one line, and the old answer — let Compose hand the deficit to the
 * last text — made "Today" a column of single letters about 165 px tall.
 *
 * These tests say what the bar does instead, and the assertions are about what
 * is *on screen*, not what is in the tree:
 *
 * - no control is squeezed (`assertIsDisplayed`, through
 *   [assertContentDescriptionDisplayed] and [assertTagDisplayed], fails for a
 *   node scrolled or clipped out of view), and
 * - the period name is one line, ellipsized, and never the thing that pushes a
 *   control out of reach.
 *
 * There is no scroll in the bar, so none of these tests scrolls: a control only
 * these tests can reach is a control the user cannot reach, which is the defect
 * the previous attempt at this fix shipped.
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
    fun `every control of the bar is on screen at a phone width`() =
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

            // Displayed, not merely present: a control parked off the right edge
            // is in the tree and useless, and that is what the scroll-based
            // attempt at this fix produced.
            assertTagDisplayed(TestTags.CALENDAR_VIEW_MODE_TRIGGER)
            assertTagDisplayed(TestTags.CALENDAR_TODAY)
            assertContentDescriptionDisplayed("Open mini calendar")
            assertContentDescriptionDisplayed("Previous")
            assertContentDescriptionDisplayed("Next")

            val strip = unclippedBoundsOfTag(TestTags.CALENDAR_CONTROLS)
            assertTrue(
                strip.left >= 0.dp && strip.right <= 360.dp,
                "the control strip runs past the window: [${strip.left}, ${strip.right}] at a 360 dp width",
            )
        }

    @Test
    fun `a period name wider than the bar truncates instead of taking the pager's room`() =
        runIsolatedComposeTest(width = 360, height = 240) {
            setContent {
                ProvideCalendarPalette {
                    MaterialTheme {
                        CalendarTopBar(
                            // ~330 dp of 20 sp text: wider than the whole bar.
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
            assertTrue(
                title.right <= 360.dp,
                "the period name runs past the window: it ends at ${title.right}",
            )

            // The worst case is the one worth measuring: a name longer than the
            // bar must not cost the calendar its month pager.
            assertContentDescriptionDisplayed("Previous")
            assertContentDescriptionDisplayed("Next")
            assertTagDisplayed(TestTags.CALENDAR_TODAY)
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
