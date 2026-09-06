package com.singularity.todo

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeAuthRepository
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/**
 * Desktop JVM smoke tests for the Singularity Todo app.
 * Runs without a window using runDesktopComposeUiTest.
 *
 * Uses [FakeAuthRepository] to bypass auth guard (defaults to [Session.Anonymous])
 * so that the full app shell — including the desktop sidebar — is rendered.
 *
 * Run with: ./gradlew :desktopApp:test
 */
class AppSmokeTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            stopKoin()
            org.koin.core.context.startKoin {
                modules(
                    platformModule(),
                    domainModule(),
                    // Override AuthRepository so AuthGuard renders the real app
                    // instead of LoginScreen (real SupabaseAuthRepository → SignedOut).
                    module { single<AuthRepository> { FakeAuthRepository() } },
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

        // The desktop sidebar is the root navigation chrome — it must be present.
        // It contains all nav destinations and is the primary navigation mechanism.
        onNodeWithTag(TestTags.DESKTOP_SIDEBAR, useUnmergedTree = true).assertExists()
    }
}
