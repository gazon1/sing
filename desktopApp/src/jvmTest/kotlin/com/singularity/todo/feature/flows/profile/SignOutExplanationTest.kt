package com.singularity.todo.feature.flows.profile

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.presentation.signOutExplanation
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The sign-out card says something different when there is no account.
 *
 * The card used to carry one sentence in both states — "You will be signed out
 * of this account on this device" — so a signed-in session and a signed-out one
 * looked alike. The distinction is a string, and this is the cheapest assertion
 * that the two states can never collapse back into one sentence.
 *
 * The address itself is rendered under [com.singularity.todo.core.ui.TestTags.AUTH_ACCOUNT_EMAIL],
 * which the account-tab flow asserts on; this test is about the *other* half of
 * the same card, the sentence under the button.
 */
@Tag("fast")
class SignOutExplanationTest {
    @Test
    fun `the signed-in explanation is not the anonymous one`() {
        val signedIn = signOutExplanation(SIGNED_IN)
        val anonymous = signOutExplanation(Session.Anonymous)

        assertTrue(signedIn.isNotBlank(), "the signed-in card explains nothing")
        assertTrue(anonymous.isNotBlank(), "the anonymous card explains nothing")
        assertNotEquals(
            signedIn,
            anonymous,
            "the two states share one sentence, so they cannot be told apart",
        )
    }

    @Test
    fun `the explanation has no state beyond the session`() {
        // Same input, same sentence: nothing about the card is time-, locale-
        // or profile-dependent, so it can be asserted at all.
        assertTrue(
            signOutExplanation(SIGNED_IN) == signOutExplanation(SIGNED_IN),
            "the explanation is not a function of the session",
        )
    }

    private companion object {
        val SIGNED_IN: Session.SignedIn =
            Session.SignedIn(
                userId = UserId.fromString("sign-out-explanation-user"),
                email = "alice@example.com",
                accessToken = "test-token",
                refreshToken = "test-refresh",
            )
    }
}
