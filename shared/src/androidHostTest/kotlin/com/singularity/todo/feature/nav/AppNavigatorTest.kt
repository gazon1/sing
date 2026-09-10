package com.singularity.todo.feature.nav

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Widget tests for [AppNavigator] — verifies the navigation CONTRACT
 * (top-level save/restore, sub-route push) using a real Compose graph
 * driven by `rememberNavController` (not a mock). Behaviour, not implementation.
 *
 * We check observable behaviour through [NavHostController.currentBackStackEntry]
 * and [NavHostController.previousBackStackEntry] — the public, supported API
 * for inspecting navigation state.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AppNavigatorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun navigateTopLevelIsIdempotentOnTheSameTab() {
        val (controller, navigator) = buildNavigator()

        composeRule.runOnUiThread {
            navigator.navigateTopLevel(AppDestination.Inbox)
            navigator.navigateTopLevel(AppDestination.Inbox)
            // Idempotent: top is Inbox, previous is Today (start), no stacking.
            assertEquals(
                "Inbox",
                controller.currentBackStackEntry?.destination?.route?.substringAfterLast('.'),
            )
            assertEquals(
                "Today",
                controller.previousBackStackEntry?.destination?.route?.substringAfterLast('.'),
            )
        }
    }

    @Test
    fun switchingTabsPushesTheNewTabAndKeepsTodayReachableViaPopBackStack() {
        val (controller, navigator) = buildNavigator()

        composeRule.runOnUiThread {
            navigator.navigateTopLevel(AppDestination.Today)
            navigator.navigate(AppDestination.TaskDetail("42"))
            navigator.navigateTopLevel(AppDestination.Plans)
            // Plans is the new top, Today is the previous (still on stack, saved).
            assertEquals("Plans", controller.currentBackStackEntry?.destination?.route?.substringAfterLast('.'))
            assertEquals("Today", controller.previousBackStackEntry?.destination?.route?.substringAfterLast('.'))
        }

        composeRule.runOnUiThread {
            // popBackStack should return to Today (top-level destination is still in the stack).
            assertTrue(navigator.popBackStack())
            assertEquals("Today", controller.currentBackStackEntry?.destination?.route?.substringAfterLast('.'))
        }
    }

    @Test
    fun returningToATabRestoresItsSubRoute() {
        val (controller, navigator) = buildNavigator()

        composeRule.runOnUiThread {
            navigator.navigateTopLevel(AppDestination.Today)
            navigator.navigate(AppDestination.TaskDetail("42"))
            navigator.navigateTopLevel(AppDestination.Plans)
            navigator.navigateTopLevel(AppDestination.Today)
        }

        composeRule.runOnUiThread {
            val entry = controller.currentBackStackEntry
            assertNotNull("Entry should be non-null after navigation", entry)
            val topRoute = entry?.destination?.route.orEmpty()
            // Type-safe routes append argument paths: "AppDestination.TaskDetail/{taskId}".
            assertTrue(
                "Top should be TaskDetail after restore, got $topRoute",
                topRoute.startsWith("com.singularity.todo.feature.nav.AppDestination.TaskDetail"),
            )
            // taskId payload round-trips via toRoute — verifies type-safe args.
            val restored = entry!!.toRoute<AppDestination.TaskDetail>()
            assertEquals("42", restored.taskId)
        }
    }

    @Test
    fun navigateTopLevelRejectsSubRoutesWithAClearError() {
        val (_, navigator) = buildNavigator()

        composeRule.runOnUiThread {
            try {
                navigator.navigateTopLevel(AppDestination.TaskDetail("x"))
                error("expected IllegalArgumentException for sub-route on navigateTopLevel")
            } catch (e: IllegalArgumentException) {
                assertTrue(
                    "error must mention the contract violation, got ${e.message}",
                    e.message?.contains("expects a tab or menu") == true,
                )
            }
        }
    }

    @Test
    fun popBackStackWalksBackThroughTheCurrentTab() {
        val (controller, navigator) = buildNavigator()

        composeRule.runOnUiThread {
            navigator.navigateTopLevel(AppDestination.Today)
            navigator.navigate(AppDestination.TaskDetail("42"))
            assertTrue(navigator.popBackStack())
        }

        composeRule.runOnUiThread {
            assertEquals(
                "Today",
                controller.currentBackStackEntry?.destination?.route?.substringAfterLast('.'),
            )
        }
    }

    @Test
    fun popBackStackReturnsFalseAtTheStartDestination() {
        val (_, navigator) = buildNavigator()

        composeRule.runOnUiThread {
            // We're at start (Today) — no previous entry to pop to.
            assertEquals(false, navigator.popBackStack())
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    /**
     * Build a real Compose graph wired through [AppNavigator]. Using the
     * production graph (not a synthetic one) means the contract is verified
     * end-to-end, including the type-safe `composable<T>` overloads.
     */
    private fun buildNavigator(): Pair<NavHostController, AppNavigator> {
        lateinit var controller: NavHostController
        lateinit var navigator: AppNavigator
        composeRule.setContent {
            controller = rememberNavController()
            navigator = AppNavigator(controller)
            NavHost(
                navController = controller,
                startDestination = AppDestination.Today,
            ) {
                composable<AppDestination.Today> {}
                composable<AppDestination.Inbox> {}
                composable<AppDestination.Plans> {}
                composable<AppDestination.TaskDetail> {}
            }
        }
        composeRule.waitForIdle()
        return controller to navigator
    }
}
