package com.singularity.todo.core.auth.oauth

import com.singularity.todo.core.error.runCatchingCancellable

/** The authorization code a consent step produced. */
data class OAuthAuthorizationCode(
    val code: String,
    val state: String,
    /**
     * The address the code arrived on.
     *
     * The token exchange has to present the same redirect URI the
     * authorization request used, and the port is only known once the socket
     * is bound — so it comes back with the code.
     */
    val redirectUri: String,
)

/** A consent step that did not produce a code, with a reason a user can act on. */
class OAuthConsentException(message: String) : Exception(message)

/**
 * The page the browser gets instead of silence.
 *
 * The user is looking at a tab in their browser; "nothing happened" reads as
 * a hang, and a hang looks like a broken app.
 */
internal const val DONE_PAGE = """
    <!doctype html>
    <html><head><meta charset="utf-8"><title>Signed in</title></head>
    <body style="font-family:system-ui;padding:2rem">
    <h1>Signed in</h1>
    <p>You can close this tab and go back to the app.</p>
    </body></html>
"""

/**
 * The consent step, as the code that uses it sees it.
 *
 * A port so that the caller — the code that exchanges the code for a token —
 * can be tested without a browser and a socket, and so that the platform
 * difference stays in the one class that actually needs it.
 */
interface OAuthConsent {
    /**
     * Sends the user to the consent page and waits for the redirect.
     *
     * @param buildUrl builds the consent URL from the redirect address the
     *   socket is listening on — the port is only known once it is bound.
     * @param expectedState the state the authorization request carried. The
     *   redirect has to echo it.
     */
    suspend fun authorize(
        buildUrl: (redirectUri: String) -> String,
        expectedState: String,
    ): Result<OAuthAuthorizationCode>
}

/**
 * Runs the consent step: open the browser, wait for the redirect, and check
 * that the answer belongs to the request that asked.
 *
 * ## Why the state check is not optional
 *
 * The redirect arrives on a port this app listens on, and the code in it is
 * worth something to whoever holds it. The state is the request's own
 * fingerprint: the app made it up, sent it, and expects to see it back. A
 * redirect carrying a different state is not an answer to this request, and a
 * code accepted without that check is a code that was meant for somebody else.
 *
 * ## Why the browser and the socket are ports
 *
 * The parts that differ per platform are the ones that touch the operating
 * system: opening a browser and holding a socket. Everything that *decides*
 * something stays here, where a test can drive it without either.
 */
class OAuthConsentFlow(private val browser: OAuthBrowser, private val socket: OAuthRedirectSocket) : OAuthConsent {
    /**
     * Sends the user to the consent page and waits for the redirect.
     *
     * @param buildUrl builds the consent URL from the redirect address the
     *   socket is listening on — the port is only known once it is bound.
     * @param expectedState the state the authorization request carried. The
     *   redirect has to echo it.
     */
    override suspend fun authorize(
        buildUrl: (redirectUri: String) -> String,
        expectedState: String,
    ): Result<OAuthAuthorizationCode> = runCatchingCancellable {
        val port = socket.bind()
        val redirectUri = OAuthLoopback.redirectUri(port)
        val url = buildUrl(redirectUri)
        if (!browser.open(url)) {
            throw OAuthConsentException("No browser on this device could open the sign-in page")
        }
        val target = socket.acceptRequestTarget(OAuthLoopback.TIMEOUT_MS).getOrThrow()
        // The browser is waiting for an answer either way: a page that says the
        // sign-in is done, or one that says it failed. Silence leaves a tab that
        // spins until the user gives up.
        socket.respondDone()
        val params = parseOAuthRedirect(target)
        params.error?.let { error ->
            throw OAuthConsentException(
                params.errorDescription?.takeIf { it.isNotBlank() }?.let { "$error: $it" } ?: error,
            )
        }
        val code = params.code?.takeIf { it.isNotBlank() }
            ?: throw OAuthConsentException("The sign-in came back without an authorization code")
        if (params.state != expectedState) {
            throw OAuthConsentException("The sign-in answer did not match the request that started it")
        }
        OAuthAuthorizationCode(code = code, state = expectedState, redirectUri = redirectUri)
    }
}
