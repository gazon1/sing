@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.core.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.assertTextNotExists
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * A screen's published bar title survives the shell's navigation.
 *
 * The shell used to clear the title slot from an effect keyed on the top-level
 * route. That effect runs after the incoming screen has composed, so it erased
 * the title the screen had just published and left the destination name in the
 * bar until the screen happened to recompose. This test mounts the real
 * publisher ([ShellOverride]), the real title rule ([ShellBarTitle]) and the
 * real navigation policy ([ClearStaleSlotsOnNavigation]); putting the clear
 * back into that policy makes the first test fail.
 */
@Tag("fast")
class ShellBarTitleSurvivesRouteChangeTest {
    @Test
    fun `a screen that publishes a title keeps it when the top-level route changes`() =
        runIsolatedComposeTest {
            val holder = ScreenActionsHolder()
            val route = mutableStateOf("today")

            setContent {
                CompositionLocalProvider(LocalScreenActions provides holder) {
                    holder.ClearStaleSlotsOnNavigation(route.value)
                    ShellOverride(title = { Text("March") })
                    ShellBarTitle(
                        screenActions = holder,
                        destinationTitle = "Calendar",
                    )
                }
            }
            waitForIdle()

            assertTextDisplayed("March")
            assertTextNotExists("Calendar")

            // The route changes while the screen stays: its title must stay too.
            route.value = "tasks"
            waitForIdle()

            assertTextDisplayed("March")
            assertTextNotExists("Calendar")
        }

    @Test
    fun `leaving the screen gives the bar back to the shell's destination name`() =
        runIsolatedComposeTest {
            val holder = ScreenActionsHolder()
            val screenOnScreen = mutableStateOf(true)

            setContent {
                CompositionLocalProvider(LocalScreenActions provides holder) {
                    holder.ClearStaleSlotsOnNavigation("today")
                    if (screenOnScreen.value) {
                        ShellOverride(title = { Text("March") })
                    }
                    ShellBarTitle(
                        screenActions = holder,
                        destinationTitle = "Calendar",
                    )
                }
            }
            waitForIdle()

            assertTextDisplayed("March")

            screenOnScreen.value = false
            waitForIdle()

            assertTextDisplayed("Calendar")
            assertTextNotExists("March")
        }

    @Test
    fun `a nested screen leaving restores the title underneath it`() =
        runIsolatedComposeTest {
            val holder = ScreenActionsHolder()
            val nestedOnScreen = mutableStateOf(true)

            setContent {
                CompositionLocalProvider(LocalScreenActions provides holder) {
                    holder.ClearStaleSlotsOnNavigation("today")
                    ShellOverride(title = { Text("Agenda") })
                    if (nestedOnScreen.value) {
                        ShellOverride(title = { Text("March") })
                    }
                    ShellBarTitle(
                        screenActions = holder,
                        destinationTitle = "Calendar",
                    )
                }
            }
            waitForIdle()

            assertTextDisplayed("March")

            nestedOnScreen.value = false
            waitForIdle()

            assertTextDisplayed("Agenda")
            assertTextNotExists("March")
        }
}
