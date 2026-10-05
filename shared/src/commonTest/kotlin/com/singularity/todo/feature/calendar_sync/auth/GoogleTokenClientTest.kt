package com.singularity.todo.feature.calendar_sync.auth

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The token-endpoint response, tested as a literal body.
 *
 * The rule that matters most is [parseTokenResponse] treating a missing `refresh_token` as
 * ordinary: Google's *refresh* responses never carry one, and code that required it would
 * break every refresh after the first.
 */
@Tag("fast")
class GoogleTokenClientTest {

    @Test
    fun `a fresh grant yields an access token and a refresh token`() {
        val parsed = parseTokenResponse(
            """
            {"access_token":"at","refresh_token":"rt","expires_in":3599,"token_type":"Bearer"}
            """.trimIndent(),
        )

        assertNotNull(parsed)
        assertEquals("at", parsed.accessToken)
        assertEquals("rt", parsed.refreshToken)
        assertTrue(parsed.canRenew, "a grant with a refresh token can outlive its access token")
    }

    /**
     * Google's refresh response has no `refresh_token` — the existing one stays valid. The
     * caller has to carry it forward, and a parser that demanded one would fail every
     * refresh after the first.
     */
    @Test
    fun `a refresh response has no refresh token, which is not an error`() {
        val parsed = parseTokenResponse("""{"access_token":"at2","expires_in":3599}""")

        assertNotNull(parsed)
        assertEquals("at2", parsed.accessToken)
        assertNull(parsed.refreshToken)
        assertTrue(!parsed.canRenew, "on its own this response cannot renew — the caller carries the old token")
    }

    @Test
    fun `expiry is converted from seconds to epoch millis`() {
        val before = System.currentTimeMillis()
        val parsed = parseTokenResponse("""{"access_token":"at","expires_in":3600}""")
        val after = System.currentTimeMillis()

        assertNotNull(parsed)
        assertTrue(
            parsed.expiresAtEpochMs in (before + 3_600_000)..(after + 3_600_000),
            "expires_in is seconds; got ${parsed.expiresAtEpochMs}",
        )
    }

    /**
     * A response with no `expires_in` is malformed, not "never expires" — treating it as
     * infinite would hide a broken grant until something else failed much later.
     */
    @Test
    fun `a response with no expires_in is rejected`() {
        assertNull(parseTokenResponse("""{"access_token":"at"}"""))
    }

    @Test
    fun `a response with no access token is rejected`() {
        assertNull(parseTokenResponse("""{"refresh_token":"rt","expires_in":3600}"""))
    }

    @Test
    fun `an error body is not parsed as a credential`() {
        assertNull(parseTokenResponse("""{"error":"invalid_grant","error_description":"Bad"}"""))
    }

    @Test
    fun `malformed json is rejected rather than throwing`() {
        assertNull(parseTokenResponse("not json at all"))
        assertNull(parseTokenResponse(""))
        assertNull(parseTokenResponse("[1,2,3]"))
    }

    @Test
    fun `a credential with a refresh token reports that it can renew`() {
        val parsed = parseTokenResponse("""{"access_token":"a","refresh_token":"r","expires_in":60}""")
        assertTrue(parsed!!.canRenew)
    }
}
