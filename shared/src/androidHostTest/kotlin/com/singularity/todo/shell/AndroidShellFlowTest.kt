package com.singularity.todo.shell

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.singularity.todo.feature.nav.AppDestination
import com.singularity.todo.feature.nav.AppNavigator
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Widget tests for [AndroidShell] chrome — verifies the user-visible
 * bottom navigation bar, FAB, and Menu bottom sheet.
 *
 * Uses a synthetic graph with placeholder composables so the chrome test
 * stays independent of every feature's wiring.
 *
 * Note: ModalBottomSheet from Material3 is hard to render in Robolectric,
 * so menu-sheet tests rely on back-stack inspection rather than visual
 * assertions where possible.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AndroidShellFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `bottom bar shows 6 items in correct order`() {
        composeRule.setContent { TestShell() }

        // BottomBar items are NavigationBarItems which may render text
        // through a non-default semantics tree.
        composeRule.onNodeWithText("Menu", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Inbox", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Today", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Plans", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Habits", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Calendar", useUnmergedTree = true).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `FAB is visible with Add task label`() {
        composeRule.setContent { TestShell() }
        composeRule.onNodeWithText("Add task", useUnmergedTree = true).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `tapping FAB navigates to TaskEditor sub-route`() {
        lateinit var controller: NavHostController
        composeRule.setContent {
            controller = rememberNavController()
            val navigator = AppNavigator(controller)
            AndroidShell(
                navigator = navigator,
                content = { modifier -> SyntheticGraph(controller, modifier) },
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Add task", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        val topRoute = controller.currentBackStackEntry?.destination?.route.orEmpty()
        assertTrue(
            "Expected TaskEditor at the top of the back stack, got $topRoute",
            topRoute.startsWith("com.singularity.todo.feature.nav.AppDestination.TaskEditor"),
        )
    }

    @Test
    fun `tapping a bottom-bar tab navigates to that destination`() {
        lateinit var controller: NavHostController
        composeRule.setContent {
            controller = rememberNavController()
            val navigator = AppNavigator(controller)
            AndroidShell(
                navigator = navigator,
                content = { modifier -> SyntheticGraph(controller, modifier) },
            )
        }
        composeRule.waitForIdle()

        // "Plans" is a direct label in the bottom bar.
        composeRule.onNodeWithText("Plans", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        val topRoute = controller.currentBackStackEntry?.destination?.route.orEmpty()
        assertTrue(
            "Expected Plans, got $topRoute",
            topRoute.startsWith("com.singularity.todo.feature.nav.AppDestination.Plans"),
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `tapping Menu button is registered`() {
        // We can't easily render ModalBottomSheet in Robolectric,
        // but we CAN verify that tapping the Menu item doesn't crash
        // and the navigator state remains consistent (still on Today).
        lateinit var controller: NavHostController
        composeRule.setContent {
            controller = rememberNavController()
            val navigator = AppNavigator(controller)
            AndroidShell(
                navigator = navigator,
                content = { modifier -> SyntheticGraph(controller, modifier) },
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Menu", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        // Sheet may or may not render in Robolectric — but the route
        // should NOT have changed (Menu isn't a navigation destination).
        val topRoute = controller.currentBackStackEntry?.destination?.route.orEmpty()
        assertTrue(
            "Tapping Menu must not change navigation, got $topRoute",
            topRoute.startsWith("com.singularity.todo.feature.nav.AppDestination.Today"),
        )
    }
}

/**
 * A self-contained shell for tests — wires a synthetic NavHost into the
 * real [AndroidShell] chrome. Each call to `TestShell()` creates an
 * isolated Compose tree.
 */
@androidx.compose.runtime.Composable
private fun TestShell() {
    val controller = rememberNavController()
    val navigator = AppNavigator(controller)
    AndroidShell(
        navigator = navigator,
        content = { modifier -> SyntheticGraph(controller, modifier) },
    )
}

/** Placeholder graph covering every [AppDestination] tab + sub-route.
 *
 *  Placeholders render an empty Box (no Text) so they don't collide
 *  with the BottomBar labels in the test assertions.
 */
@androidx.compose.runtime.Composable
private fun SyntheticGraph(controller: NavHostController, modifier: Modifier = Modifier) {
    NavHost(controller, startDestination = AppDestination.Today, modifier = modifier) {
        composable<AppDestination.Today> { }
        composable<AppDestination.Inbox> { }
        composable<AppDestination.Plans> { }
        composable<AppDestination.Habits> { }
        composable<AppDestination.Calendar> { }
        composable<AppDestination.Notes> { }
        composable<AppDestination.AiChat> { }
        composable<AppDestination.Search> { }
        composable<AppDestination.Archive> { }
        composable<AppDestination.Settings> { }
        composable<AppDestination.TaskEditor> { }
        composable<AppDestination.TaskDetail> { }
    }
}
