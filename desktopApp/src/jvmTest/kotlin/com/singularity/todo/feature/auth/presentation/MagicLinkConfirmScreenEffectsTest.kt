package com.singularity.todo.feature.auth.presentation

import androidx.compose.ui.test.ExperimentalTestApi
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.auth.AuthUiEvent
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The magic-link screen's reaction to the auth event bus.
 *
 * ## What this pins
 *
 * The screen keeps no copy of an event: it presents it where it arrives. So
 * the question this asks is the one the old code could not answer — when the
 * view model says the verification failed, does the user find out?
 *
 * The neutral notice is the case worth pinning twice over: "check your inbox"
 * is not a failure, and a screen that told the user so in the shape of an
 * error would send them back to the form.
 *
 * It mounts the screen's reaction on its own, with an event flow of its own,
 * rather than the whole screen with a view model behind it: the wiring under
 * test is the collection and the presentation, and a stub repository would only
 * add a second thing that could be wrong.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class MagicLinkConfirmScreenEffectsTest {
    @Test
    fun `a failed verification reaches the user as an error`() =
        runIsolatedComposeTest {
            setContent {
                MagicLinkConfirmScreenEffects(
                    events = flowOf(AuthUiEvent.Error("Verification failed")),
                    onNavigateHome = {},
                )
            }

            assertTagDisplayed(TestTags.RESULT_DIALOG)
        }

    @Test
    fun `a neutral notice reaches the user as a snackbar, never as an error`() =
        runIsolatedComposeTest {
            setContent {
                MagicLinkConfirmScreenEffects(
                    events = flowOf(AuthUiEvent.Message("A new sign-in link was sent to user@example.com")),
                    onNavigateHome = {},
                )
            }

            assertTagDisplayed(TestTags.SNACKBAR_MESSAGE)
        }
}
