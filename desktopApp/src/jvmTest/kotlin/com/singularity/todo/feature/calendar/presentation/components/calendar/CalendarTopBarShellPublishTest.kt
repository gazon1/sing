@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.calendar.presentation.components.calendar

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.LocalScreenActions
import com.singularity.todo.core.ui.components.ScreenActionsHolder
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.domain.state.CalendarUiState
import com.singularity.todo.feature.calendar.presentation.theme.ProvideCalendarPalette
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The calendar's bar, on the publishing side.
 *
 * On Android the shell owns the only bar, so the calendar does not draw a
 * second one: it publishes the month it is showing to the shell through
 * [com.singularity.todo.core.ui.components.ShellOverride], and the shell draws
 * that month instead of the destination's own name.
 *
 * The publisher's own contract is pinned by `ShellBarOverrideTest`, but that
 * test mounts the publisher alone. Nothing said that the calendar — the only
 * production caller — still publishes. Had the bar stopped calling the
 * publisher, or published an empty label, the shell bar would have fallen back
 * to the word "Calendar", and every test would have stayed green: the
 * publisher's test mounts the publisher, and the desktop calendar tests can
 * only see the header the calendar draws for itself (the desktop shell has no
 * holder, so the publication path is a no-op there).
 *
 * So the calendar's bar is mounted here with a holder provided through
 * [LocalScreenActions], and what it publishes is read the way the shell reads
 * it: the month label, the navigation icon, and the flag that tells the shell
 * to draw them instead of its own.
 *
 * The published title and icon are composable lambdas — the shell invokes them
 * in its own bar — so the test invokes them too, in [PublishedShellBar]. That
 * is what makes an empty publication fail: a title that is present but says
 * nothing would pass a null check and fail the text of the month.
 */
@Tag("fast")
class CalendarTopBarShellPublishTest {
    private val today = LocalDate(2026, 10, 12)

    private fun barState(headerLabel: String) =
        CalendarUiState.Loaded(
            anchor = LocalDate(2026, 9, 1),
            viewMode = CalendarViewMode.MONTH,
            visibleDates = persistentListOf(today),
            tasksByDate = persistentMapOf(),
            today = today,
            selectedDate = today,
            headerLabel = headerLabel,
        )

    @Test
    fun `the calendar publishes the month it shows to the shell bar`() =
        runIsolatedComposeTest {
            val holder = ScreenActionsHolder()
            val state = barState("September 2026")

            setContent {
                CompositionLocalProvider(LocalScreenActions provides holder) {
                    ProvideCalendarPalette {
                        MaterialTheme {
                            Column {
                                CalendarTopBar(state = state, today = today, onIntent = {})
                                PublishedShellBar(holder)
                            }
                        }
                    }
                }
            }
            waitForIdle()

            assertTrue(
                holder.override,
                "the calendar takes the shell bar over only by saying so: without the " +
                    "override the shell draws the destination's own name",
            )
            assertNotNull(
                holder.title,
                "the calendar published no title: the shell bar would show the " +
                    "destination's name instead of the month on screen",
            )
            assertNull(
                holder.navigationIcon,
                "a calendar without a back action must publish no navigation icon: the " +
                    "shell keeps its own back button, which knows where the shell is",
            )
            assertTagTextEquals(
                TestTags.CALENDAR_MONTH_TITLE,
                state.headerLabel,
            )
            assertTagNotExists(TestTags.TOP_BAR_BACK_BUTTON)
        }

    @Test
    fun `a back action is published as the shell bar's navigation icon`() =
        runIsolatedComposeTest {
            val holder = ScreenActionsHolder()
            val state = barState("September 2026")
            var backPresses = 0

            setContent {
                CompositionLocalProvider(LocalScreenActions provides holder) {
                    ProvideCalendarPalette {
                        MaterialTheme {
                            Column {
                                CalendarTopBar(
                                    state = state,
                                    today = today,
                                    onIntent = {},
                                    onBack = { backPresses++ },
                                )
                                PublishedShellBar(holder)
                            }
                        }
                    }
                }
            }
            waitForIdle()

            assertTrue(
                holder.override,
                "the calendar takes the shell bar over only by saying so",
            )
            assertNotNull(
                holder.navigationIcon,
                "the calendar has a back action but published no navigation icon: the " +
                    "shell would keep its own back button and the calendar's would be lost",
            )
            assertTagTextEquals(
                TestTags.CALENDAR_MONTH_TITLE,
                state.headerLabel,
            )

            // The published icon is the calendar's own back button, not some
            // other icon: the tag is the calendar's, and pressing it reaches the
            // action the screen handed in.
            clickTag(TestTags.TOP_BAR_BACK_BUTTON)
            assertEquals(
                1,
                backPresses,
                "the published navigation icon is not the calendar's back action",
            )
        }
}

/**
 * What the calendar published, drawn the way the shell draws it.
 *
 * The shell renders the published title and navigation icon in its own bar, so
 * the test renders them in one place of its own. The icon goes inside a
 * [Surface]: an icon drawn outside a surface has no content colour to inherit,
 * and the composition fails on it.
 */
@Composable
private fun PublishedShellBar(holder: ScreenActionsHolder) {
    val title = holder.title
    if (title != null) {
        title()
    }
    val navigationIcon = holder.navigationIcon
    if (navigationIcon != null) {
        Surface {
            navigationIcon()
        }
    }
}
