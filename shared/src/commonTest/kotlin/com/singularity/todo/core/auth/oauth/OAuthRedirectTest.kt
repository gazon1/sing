package com.singularity.todo.core.auth.oauth

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The redirect that comes back from the provider, read as it arrives on the wire.
 *
 * ## What can go wrong here
 *
 * The target is a browser's request line, not a structured object: it carries a
 * path, a query string and sometimes a fragment. Everything the app decides
 * about the answer — whether the user agreed, whether the answer belongs to
 * this request — is read from it here, and a parser that drops a value turns a
 * refusal into a mystery.
 */
@Tag("fast")
class OAuthRedirectTest {

    /** The ordinary success: a code and the state that asked for it. */
    @Test
    fun `a code and a state are read`() {
        val params = parseOAuthRedirect("/?code=abc123&state=xyz")

        assertEquals("abc123", params.code)
        assertEquals("xyz", params.state)
        assertNull(params.error)
    }

    /**
     * The refusal. Google sends `error=access_denied` when the user says no,
     * and the app has to report that rather than wait for a code that is not
     * coming.
     */
    @Test
    fun `a refusal is read`() {
        val params = parseOAuthRedirect("/?error=access_denied&error_description=The+user+said+no")

        assertEquals("access_denied", params.error)
        assertNull(params.code)
    }

    /**
     * Percent-encoding. A state is base64url and may carry characters the
     * browser escapes on the way out; an undecoded value never matches, and the
     * sign-in is reported as "the answer did not match the request".
     */
    @Test
    fun `a percent-encoded value is decoded`() {
        val params = parseOAuthRedirect("/?code=a%2Fb%3Dc&state=x%20y")

        assertEquals("a/b=c", params.code)
        assertEquals("x y", params.state)
    }

    /**
     * `+` is left alone.
     *
     * In a query string `+` means a space, but Google's values are base64url
     * and can carry a literal `+`; decoding it as a space corrupts the value,
     * and the mismatch is reported as a foreign answer.
     */
    @Test
    fun `a plus sign stays a plus sign`() {
        val params = parseOAuthRedirect("/?code=a+b")

        assertEquals("a+b", params.code)
    }

    /** A target with no query carries nothing, and that is not an error. */
    @Test
    fun `a target without a query has no parameters`() {
        val params = parseOAuthRedirect("/")

        assertNull(params.code)
        assertNull(params.state)
        assertNull(params.error)
    }

    /**
     * The fragment is not part of the answer.
     *
     * The authorization-code flow puts everything in the query; a fragment would
     * mean the provider answered in the implicit flow, which this project does
     * not use — so a code hiding there is not a code this app can redeem.
     */
    @Test
    fun `a fragment is not read as the answer`() {
        val params = parseOAuthRedirect("/#code=abc123&state=xyz")

        assertNull(params.code)
        assertNull(params.state)
    }

    /** An empty pair between two separators is not a parameter. */
    @Test
    fun `an empty pair is skipped`() {
        val params = parseOAuthRedirect("/?code=abc123&&state=xyz")

        assertEquals("abc123", params.code)
        assertEquals("xyz", params.state)
    }
}
