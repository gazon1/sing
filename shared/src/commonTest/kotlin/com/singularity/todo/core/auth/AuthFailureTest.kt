package com.singularity.todo.core.auth

import com.singularity.todo.core.error.AppError
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What a failed authentication call means.
 *
 * ## Why the vocabulary is worth a test
 *
 * The codes are the contract with the dashboard and the messages are the contract
 * with the person. A code that changes silently splits one group of reports into
 * two, and a message that stops naming the cause sends the person to look in the
 * wrong place. Both are quiet: nothing fails, the code just stops being the code.
 *
 * ## And why the refresh rule is worth one
 *
 * A failed refresh has two answers, and the caller acts differently on them: a
 * rejected token ends the session, a timeout does not. Getting that backwards
 * signs a person out on a train.
 */
@Tag("fast")
class AuthFailureTest {

    /** The code for an unconfigured project is the one the dashboard groups by. */
    @Test
    fun `the unconfigured project has its own code`() {
        val failure = AuthFailure.notConfigured()

        assertIs<AppError.Validation>(failure)
        assertEquals("auth.not_configured", failure.code)
        assertTrue(
            failure.message.orEmpty().contains("nothing to sign in to"),
            "the message must say what is missing: ${failure.message}",
        )
    }

    /**
     * The answer the provider gave is in the message.
     *
     * "The provider accepted 'sign in' but left no session behind" names the call
     * that was answered, and a person reading the screen knows which of the three
     * sign-in paths failed.
     */
    @Test
    fun `the missing session names the call that was answered`() {
        val failure = AuthFailure.noSession("sign in")

        assertIs<AppError.Unauthorized>(failure)
        assertEquals("auth.no_session", failure.code)
        assertTrue(
            failure.message.orEmpty().contains("sign in"),
            "the message must name the call: ${failure.message}",
        )
    }

    /**
     * A verified link without a session is its own sentence.
     *
     * The link was good — the server says so — and there is still nothing to sign
     * in with. That is not the same as "the provider left no session behind".
     */
    @Test
    fun `the verified link without a session says so`() {
        val failure = AuthFailure.verifiedWithoutSession()

        assertIs<AppError.Unauthorized>(failure)
        assertEquals("auth.no_session", failure.code)
        assertTrue(
            failure.message.orEmpty().contains("Magic link"),
            "the message must name the link: ${failure.message}",
        )
    }

    /**
     * A rejected token ends the session.
     *
     * This is the one answer the repository acts on: it clears its credentials, so
     * that the app stops sending a token the server will refuse.
     */
    @Test
    fun `a rejected token ends the session`() {
        assertEquals(
            RefreshOutcome.SessionIsGone,
            AuthFailure.refreshOutcome(RefreshFailureReason.Rejected),
        )
    }

    /**
     * Anything else leaves the session alone.
     *
     * A timeout, a lost connection or a server that is briefly unwell is not a
     * verdict on the token — REQ-UA-003. Signing the person out over a network blip
     * throws away a session that would have worked a second later.
     */
    @Test
    fun `a failure that is not about the token leaves the session alone`() {
        assertEquals(
            RefreshOutcome.KeepSession,
            AuthFailure.refreshOutcome(RefreshFailureReason.Transient),
        )
    }
}
