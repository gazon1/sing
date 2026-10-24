package com.singularity.todo.core.auth.oauth

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The consent step, driven by fakes instead of a browser and a socket.
 *
 * ## Why the whole step is here and not in the platform
 *
 * The browser and the socket are the parts that differ per platform, and they
 * are ports here. Everything that *decides* something — whether the answer
 * belongs to this request, what a refusal means, whether the browser could be
 * opened at all — is common code, and this is where it is checked.
 */
@Tag("fast")
class OAuthConsentFlowTest {

    /** Records the URL it was asked to open, and reports whether it could. */
    private class FakeBrowser(private val canOpen: Boolean = true) : OAuthBrowser {
        var openedUrl: String? = null

        override suspend fun open(url: String): Boolean {
            openedUrl = url
            return canOpen
        }
    }

    /** Hands back a target as if a browser had sent it. */
    private class FakeSocket(private val target: String? = null, private val failure: Throwable? = null) :
        OAuthRedirectSocket {
        var boundPort = 43123
        var responded = false
        var closed = false
        var askedTimeoutMs: Long? = null

        override suspend fun bind(): Int = boundPort

        override suspend fun acceptRequestTarget(timeoutMs: Long): Result<String> {
            askedTimeoutMs = timeoutMs
            return failure?.let { Result.failure(it) } ?: Result.success(requireNotNull(target))
        }

        override suspend fun respondDone() {
            responded = true
        }

        override fun close() {
            closed = true
        }
    }

    /** The ordinary success: the user agreed, and the state matches. */
    @Test
    fun `a redirect with a code and the matching state yields the code`() = runTest {
        val browser = FakeBrowser()
        val socket = FakeSocket(target = "/?code=the-code&state=the-state")

        val result = OAuthConsentFlow(browser, socket).authorize(
            buildUrl = { "https://example.test/authorize?redirect_uri=$it" },
            expectedState = "the-state",
        )

        val code = assertIs<OAuthAuthorizationCode>(result.getOrThrow())
        assertEquals("the-code", code.code)
        assertEquals("the-state", code.state)
    }

    /**
     * The exchange has to present the same redirect URI the sign-in used, and
     * the port is only known once the socket is bound — so it travels back with
     * the code.
     */
    @Test
    fun `the code comes with the loopback address of the bound port`() = runTest {
        val socket = FakeSocket(target = "/?code=c&state=s").apply { boundPort = 51234 }

        val result = OAuthConsentFlow(FakeBrowser(), socket).authorize(
            buildUrl = { "https://example.test/authorize?redirect_uri=$it" },
            expectedState = "s",
        )

        val code = assertIs<OAuthAuthorizationCode>(result.getOrThrow())
        assertEquals("http://localhost:51234", code.redirectUri)
    }

    /** The URL is built with the address the socket is listening on. */
    @Test
    fun `the consent URL carries the bound redirect address`() = runTest {
        val browser = FakeBrowser()
        val socket = FakeSocket(target = "/?code=c&state=s").apply { boundPort = 44444 }

        OAuthConsentFlow(browser, socket).authorize(
            buildUrl = { redirectUri -> "https://example.test/authorize?redirect_uri=$redirectUri" },
            expectedState = "s",
        )

        assertTrue(
            browser.openedUrl?.contains("redirect_uri=http://localhost:44444") == true,
            "got ${browser.openedUrl}",
        )
    }

    /**
     * The state check.
     *
     * A redirect carrying another state is not an answer to this request: the
     * code in it was meant for somebody else, and accepting it would redeem a
     * code this app never asked for.
     */
    @Test
    fun `a state that does not match the request is refused`() = runTest {
        val socket = FakeSocket(target = "/?code=c&state=somebody-elses-state")

        val result = OAuthConsentFlow(FakeBrowser(), socket).authorize(
            buildUrl = { "https://example.test/authorize" },
            expectedState = "the-state",
        )

        val error = result.exceptionOrNull()
        assertTrue(
            error is OAuthConsentException && error.message?.contains("did not match") == true,
            "got $error",
        )
    }

    /** The user said no: the provider's own reason is reported. */
    @Test
    fun `a refusal is reported with the provider's reason`() = runTest {
        val socket = FakeSocket(target = "/?error=access_denied&error_description=User+refused")

        val result = OAuthConsentFlow(FakeBrowser(), socket).authorize(
            buildUrl = { "https://example.test/authorize" },
            expectedState = "s",
        )

        val error = result.exceptionOrNull()
        assertTrue(
            error is OAuthConsentException && error.message?.contains("access_denied") == true,
            "got $error",
        )
    }

    /** A redirect with neither a code nor an error is a broken answer. */
    @Test
    fun `a redirect without a code is refused`() = runTest {
        val socket = FakeSocket(target = "/?state=s")

        val result = OAuthConsentFlow(FakeBrowser(), socket).authorize(
            buildUrl = { "https://example.test/authorize" },
            expectedState = "s",
        )

        val error = result.exceptionOrNull()
        assertTrue(
            error is OAuthConsentException && error.message?.contains("without an authorization code") == true,
            "got $error",
        )
    }

    /**
     * A device with no browser is a failure, not a wait.
     *
     * The alternative is to sit on the socket for five minutes waiting for a
     * redirect that cannot come, with the button looking broken the whole time.
     */
    @Test
    fun `a browser that cannot be opened is reported instead of waited for`() = runTest {
        val socket = FakeSocket(target = "/?code=c&state=s")

        val result = OAuthConsentFlow(FakeBrowser(canOpen = false), socket).authorize(
            buildUrl = { "https://example.test/authorize" },
            expectedState = "s",
        )

        val error = result.exceptionOrNull()
        assertTrue(
            error is OAuthConsentException && error.message?.contains("No browser") == true,
            "got $error",
        )
        assertEquals(null, socket.askedTimeoutMs, "nothing should have been waited for")
    }

    /**
     * The tab gets an answer either way.
     *
     * A browser left hanging on a refused sign-in spins until the user gives
     * up, and reads as a broken app rather than a cancelled one.
     */
    @Test
    fun `the browser is answered even when the redirect was refused`() = runTest {
        val socket = FakeSocket(target = "/?error=access_denied")

        OAuthConsentFlow(FakeBrowser(), socket).authorize(
            buildUrl = { "https://example.test/authorize" },
            expectedState = "s",
        )

        assertTrue(socket.responded, "the tab must not be left hanging")
    }

    /** A timeout on the socket is reported as what it is. */
    @Test
    fun `a socket timeout is reported as a failure`() = runTest {
        val socket = FakeSocket(failure = OAuthConsentException("The sign-in took too long"))

        val result = OAuthConsentFlow(FakeBrowser(), socket).authorize(
            buildUrl = { "https://example.test/authorize" },
            expectedState = "s",
        )

        assertTrue(result.isFailure, "a timeout must not read as a sign-in")
    }
}
