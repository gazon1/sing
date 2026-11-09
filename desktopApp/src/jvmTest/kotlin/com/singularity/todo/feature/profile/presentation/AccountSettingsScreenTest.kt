@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.profile.presentation

import androidx.compose.material3.MaterialTheme
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.profile.Profile
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.test.helpers.assertContentDescriptionDisplayed
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.time.Instant

/**
 * The account screen says who is signed in.
 *
 * #1712: the screen held two cards both headed "Account" and named nobody —
 * a signed-in session and a signed-out one looked the same, and the signed-in
 * address was rendered nowhere. The fix renamed the cards and put the address
 * on the screen, which is exactly the kind of change a test has to pin: the
 * headings are text, and text drifts.
 *
 * The screen is rendered through its stateless preview content, because the
 * screen itself reads the session out of Koin and the navigator out of the nav
 * graph, and a test that has to mount the whole app to learn whether a string
 * is on it is a test that fails for the wrong reason.
 *
 * A Compose UI harness counts as slow by the tag convention: it crosses the
 * process boundary of a real window and can block.
 */
@Tag("slow")
@Tag("slow:compose")
class AccountSettingsScreenTest {
    @Test
    fun `a signed in session names the account`() =
        runIsolatedComposeTest(height = 1200) {
            setContent {
                MaterialTheme {
                    AccountSettingsScreenPreviewContent(
                        activeProfile = profile(),
                        onNavigateToProfileSwitcher = {},
                        onChangePassword = {},
                        onSignOut = {},
                        session = signedIn(),
                    )
                }
            }

            assertTextDisplayed("Signed in")
            assertTagDisplayed(TestTags.AUTH_ACCOUNT_EMAIL)
            assertTextDisplayed("alice@example.com")
            // The way out of a signed-in session is signing out of it.
            assertTagDisplayed(TestTags.AUTH_SIGN_OUT_BUTTON)
        }

    @Test
    fun `an anonymous session says so and names no account`() =
        runIsolatedComposeTest(height = 1200) {
            setContent {
                MaterialTheme {
                    AccountSettingsScreenPreviewContent(
                        activeProfile = profile(),
                        onNavigateToProfileSwitcher = {},
                        onChangePassword = {},
                        onSignOut = {},
                        session = Session.Anonymous(userId = UserId.anonymous),
                    )
                }
            }

            assertTextDisplayed("Not signed in")
            assertTagNotExists(TestTags.AUTH_ACCOUNT_EMAIL)
            // The way out of an anonymous session is attaching an account —
            // the signed-in screen must not offer it.
            assertTagDisplayed(TestTags.AUTH_ATTACH_ACCOUNT_BUTTON)
            // And there is nothing to sign out of. The card used to be drawn
            // for an anonymous session too, with wording that had to explain
            // that the button signs out of nothing.
            assertTagNotExists(TestTags.AUTH_SIGN_OUT_BUTTON)
        }

    @Test
    fun `a profile with no emoji draws a person icon`() =
        runIsolatedComposeTest {
            setContent {
                MaterialTheme {
                    ProfileAvatar(emoji = "")
                }
            }

            assertContentDescriptionDisplayed("Profile avatar")
        }

    @Test
    fun `a profile with an emoji draws the emoji`() =
        runIsolatedComposeTest {
            setContent {
                MaterialTheme {
                    ProfileAvatar(emoji = "🏠")
                }
            }

            assertTextDisplayed("🏠")
        }

    private fun profile(emoji: String = "🏠") =
        Profile(
            id = ProfileId.default,
            name = "Personal",
            emoji = emoji,
            colorIdx = 0,
            isDefault = true,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )

    private fun signedIn() =
        Session.SignedIn(
            userId = UserId.fromString("preview-user-id"),
            email = "alice@example.com",
            accessToken = "preview-token",
            refreshToken = "preview-refresh",
        )
}
