package com.singularity.todo.feature.auth

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The login screen's reaction to the auth event bus.
 *
 * ## What this pins
 *
 * The screen keeps no copy of an event: it presents it where it arrives. So
 * the question this asks is the one the old code could not answer — when the
 * view model says something went wrong, does the user find out?
 *
 * It mounts the effects on their own, with an event flow of its own, rather
 * than the whole screen with a view model behind it: the wiring under test is
 * the collection and the presentation, and a stub repository would only add a
 * second thing that could be wrong.
 *
 * ## Why the surfaces are asserted by tag
 *
 * The dialog and the snackbar each open their own window, and the copy in them
 * is the translator's. A tag says which surface appeared without saying what it
 * said, which is the only claim that survives a translation change.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class LoginScreenEffectsTest {
    @Test
    fun `a failed sign-in reaches the user as an error`() =
        runIsolatedComposeTest {
            setContent {
                LoginScreenEffects(
                    events = flowOf(AuthUiEvent.Error("Invalid login credentials")),
                    onNavigateHome = {},
                )
            }

            assertTagDisplayed(TestTags.RESULT_DIALOG)
        }

    @Test
    fun `a neutral notice reaches the user as a snackbar`() =
        runIsolatedComposeTest {
            setContent {
                LoginScreenEffects(
                    events = flowOf(AuthUiEvent.Message("Account created. Check your email.")),
                    onNavigateHome = {},
                )
            }

            assertTagDisplayed(TestTags.SNACKBAR_MESSAGE)
        }

    @Test
    fun `the navigation event navigates instead of notifying`() =
        runIsolatedComposeTest {
            var navigated = false

            setContent {
                LoginScreenEffects(
                    events = flowOf(AuthUiEvent.NavigateToHome),
                    onNavigateHome = { navigated = true },
                )
            }

            waitUntil(
                conditionDescription = "the navigation event reaches the screen",
                timeoutMillis = 5_000,
            ) { navigated }
        }
}
