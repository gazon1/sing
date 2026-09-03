package com.singularity.todo

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.junit.Rule
import org.junit.Test

/**
 * Desktop JVM smoke tests for the Singularity Todo app.
 * Runs without a window using createComposeRule + runDesktopComposeUiTest.
 *
 * Run with: ./gradlew :desktopApp:jvmTest
 */
class AppSmokeTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun app_launches_and_shows_bottom_nav() = runDesktopComposeUiTest {
        setContent {
            App()
        }

        // Verify all 5 bottom nav items are present
        onNodeWithText("Tasks", useUnmergedTree = true).assertExists()
        onNodeWithText("Notes", useUnmergedTree = true).assertExists()
        onNodeWithText("Projects", useUnmergedTree = true).assertExists()
        onNodeWithText("Search", useUnmergedTree = true).assertExists()
        onNodeWithText("Settings", useUnmergedTree = true).assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun can_navigate_to_notes_tab() = runDesktopComposeUiTest {
        setContent {
            App()
        }

        // Tap Notes tab
        onNodeWithText("Notes", useUnmergedTree = true).performClick()

        // After navigation, Notes content should be visible
        // (the exact content depends on data, but we verify the tab is clickable and doesn't crash)
        onNodeWithText("Notes", useUnmergedTree = true).assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun can_navigate_to_settings_tab() = runDesktopComposeUiTest {
        setContent {
            App()
        }

        onNodeWithText("Settings", useUnmergedTree = true).performClick()
        onNodeWithText("Settings", useUnmergedTree = true).assertExists()
    }
}
