package com.singularity.todo.feature.calendar_sync.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server's answer, read.
 *
 * ## Why the wire format is worth a test of its own
 *
 * A renamed field reads as a null token, and a null token reads as "not connected".
 * The failure then looks like a missing grant rather than a broken contract, and the
 * user is told to reconnect when the server is the thing that changed. These tests
 * pin the format: a canned body in, a credential out.
 */
@Tag("fast")
class SupabaseGoogleTokenGatewayTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun body(vararg pairs: Pair<String, String>): kotlinx.serialization.json.JsonObject =
        buildJsonObject { pairs.forEach { (key, value) -> put(key, value) } }

    /** The ordinary answer: a token and when it stops being valid. */
    @Test
    fun `the token and the expiry are read`() {
        val answer = body(
            "access_token" to "the-token",
            "expires_at_ms" to "1700000000000",
        ).jsonObject

        val credentials = answer.readGoogleCredentials()

        assertEquals("the-token", credentials.accessToken)
        assertEquals(1700000000000L, credentials.expiresAtEpochMs)
    }

    /**
     * The refresh token never comes back to the device.
     *
     * It is the server's credential: a device that held it would have to renew it
     * with the client secret, and the client secret is the reason the exchange
     * happens on the server at all.
     */
    @Test
    fun `the answer carries no refresh token`() {
        val answer = body("access_token" to "the-token").jsonObject

        val credentials = answer.readGoogleCredentials()

        assertNull(credentials.refreshToken)
        assertTrue(!credentials.canRenew, "a server-issued token cannot be renewed on the device")
    }

    /**
     * A response without a token is a broken response.
     *
     * The alternative is a credential with an empty access token, and the first
     * thing that happens with that is a `401` from Google — which says nothing about
     * the server that answered.
     */
    @Test
    fun `a response without a token is refused`() {
        val answer = body("expires_at_ms" to "1700000000000").jsonObject

        val failure = assertFailsWith<GoogleAuthorizationException> {
            answer.readGoogleCredentials()
        }

        assertTrue(failure.message.orEmpty().contains("access token"), "got ${failure.message}")
    }

    /** An empty token is no token. */
    @Test
    fun `an empty token is refused`() {
        val answer = body("access_token" to "  ").jsonObject

        assertFailsWith<GoogleAuthorizationException> {
            answer.readGoogleCredentials()
        }
    }

    /**
     * A response without an expiry still yields a usable token.
     *
     * The expiry is what tells the app when to ask again. Without it the app would
     * have to treat every token as expired and ask on every call — or, worse, treat
     * it as valid forever and use a dead token. Google's own figure is an hour.
     */
    @Test
    fun `a response without an expiry is given the default lifetime`() {
        val answer = body("access_token" to "the-token").jsonObject

        val credentials = answer.readGoogleCredentials()

        assertTrue(
            credentials.expiresAtEpochMs > System.currentTimeMillis(),
            "the default expiry must be in the future",
        )
        assertTrue(
            credentials.expiresAtEpochMs <= System.currentTimeMillis() + DEFAULT_GOOGLE_TOKEN_EXPIRY_MS,
            "the default expiry must not be further out than Google's own hour",
        )
    }

    /** The answer is read from the text the server sent. */
    @Test
    fun `the answer is parsed from the server's text`() {
        val text = """{"access_token":"the-token","expires_at_ms":1700000000000,"email":"a@b.c"}"""

        val answer = json.parseToJsonElement(text).jsonObject
        val credentials = answer.readGoogleCredentials()

        assertEquals("the-token", credentials.accessToken)
    }
}
