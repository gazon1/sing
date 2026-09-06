package com.singularity.todo

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.singularity.todo.core.ui.TestTags
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.koin.core.context.stopKoin

/**
 * Desktop JVM smoke tests for the Singularity Todo app.
 * Runs without a window using runDesktopComposeUiTest.
 *
 * Run with: ./gradlew :desktopApp:test
 */
class AppSmokeTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            // Start Koin once for all tests so that koinInject() calls in App() work.
            // Modules mirror desktopApp/main.kt (real platformModule + domainModule).
            // stopKoin() is called in tearDown to allow re-run in the same process.
            stopKoin()
            org.koin.core.context.startKoin {
                modules(
                    com.singularity.todo.core.di.platformModule(),
                    com.singularity.todo.core.di.domainModule(),
                )
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            stopKoin()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun app_launches_and_shows_desktop_sidebar() = runDesktopComposeUiTest {
        setContent {
            App()
        }

        // The core thing we need to verify is that the desktop sidebar is rendered.
        // The sidebar has a unique test tag and contains all nav destinations.
        onNodeWithTag(TestTags.DESKTOP_SIDEBAR, useUnmergedTree = true).assertExists()
    }
}
