package com.singularity.todo.feature.calendarSync.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire contract with the project's own Edge Function.
 *
 * ## Why the contract is worth a test of its own
 *
 * The Edge Function lives in this repository, under
 * `supabase/functions/google-oauth-exchange`, and the app is its client. The two
 * can drift apart silently: a renamed action is an answer the function does not
 * understand, and a wrong status code turns "the grant is already gone" into a
 * failure the person reads as "something went wrong".
 *
 * The contract is a project's decision and it lives in a file without the SDK in
 * it, so it can be checked here — with no server, no network and no SDK.
 */
@Tag("fast")
class GoogleTokenExchangeWireTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** A canned body, the way the server would send it. */
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

    /** The exchange asks for the code to be redeemed, and says which code. */
    @Test
    fun `the exchange request carries the code`() {
        val request = GoogleTokenExchangeWire.exchangeRequest("the-code")

        assertEquals("exchange", request["action"]?.jsonPrimitive?.content)
        assertEquals("the-code", request["code"]?.jsonPrimitive?.content)
    }

    /** The token request carries no code: the server knows the account. */
    @Test
    fun `the token request asks for the current token`() {
        val request = GoogleTokenExchangeWire.tokenRequest()

        assertEquals("token", request["action"]?.jsonPrimitive?.content)
        assertTrue(
            request.keys.none { it == "code" },
            "the token request must not carry a code: $request",
        )
    }

    /** The revoke request says what it wants, and carries nothing else. */
    @Test
    fun `the revoke request asks for the grant to be cancelled`() {
        val request = GoogleTokenExchangeWire.revokeRequest()

        assertEquals("revoke", request["action"]?.jsonPrimitive?.content)
        assertEquals(1, request.size, "the revoke request carries the action and nothing else: $request")
    }

    /**
     * A revoke that found nothing is not a failure.
     *
     * The server holds no grant for this account: there is nothing to revoke, and
     * the account is disconnected either way. A person who pressed "Disconnect"
     * must not be told that something went wrong.
     */
    @Test
    fun `a revoke that found nothing is not a failure`() {
        assertTrue(GoogleTokenExchangeWire.revokeIsDone(200), "the grant was cancelled")
        assertTrue(GoogleTokenExchangeWire.revokeIsDone(404), "there was no grant to cancel")
        assertFalse(GoogleTokenExchangeWire.revokeIsDone(500), "the server failed")
        assertFalse(GoogleTokenExchangeWire.revokeIsDone(403), "the server refused")
    }

    /** The advice for an unconfigured project says what to do about it. */
    @Test
    fun `the refusal for an unconfigured project says what to do`() {
        val failure = GoogleTokenExchangeWire.noCloudServerConfigured()

        assertTrue(
            failure.message.orEmpty().contains("Account settings"),
            "the message must say where to fix it: ${failure.message}",
        )
    }
}
