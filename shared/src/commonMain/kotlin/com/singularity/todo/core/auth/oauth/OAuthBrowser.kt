package com.singularity.todo.core.auth.oauth

/**
 * Opens a URL in the platform's browser.
 *
 * The consent step has to happen in a real browser: Google refuses to render
 * its consent screen inside an embedded user agent, and a browser that already
 * holds the user's Google session removes a password prompt from the flow.
 */
interface OAuthBrowser {
    /**
     * Opens [url].
     *
     * @return false when no browser could be opened — a device with none, or a
     *   headless machine. The caller reports that as a failure rather than
     *   waiting for a redirect that will never come.
     */
    suspend fun open(url: String): Boolean

    /**
     * Whether a browser can be opened at all.
     *
     * The consent step needs one, and the answer is not always yes: a headless
     * machine has neither a display nor a browser, and there the "Connect" button
     * has to be disabled *before* the user presses it rather than failing after.
     *
     * This is a separate question from [open] on purpose: [open] acts, and asking it
     * to find out whether it could act would open a window to answer a question.
     */
    fun isAvailable(): Boolean
}

/**
 * Waits for the provider to redirect back to this app.
 *
 * The listener owns the socket; everything about *what* the redirect means —
 * parsing it, checking the state, mapping the error — lives in common code
 * that both platforms share and that a test can drive without a socket.
 */
interface OAuthRedirectListener {
    /**
     * Waits for one redirect and returns the raw request target.
     *
     * @param timeoutMs how long to wait before giving up.
     */
    suspend fun awaitRedirect(timeoutMs: Long): Result<String>
}

/**
 * The loopback address Google's console has to know about.
 *
 * Google matches a loopback redirect URI on the host and ignores the port, so
 * the port here is a free one the operating system hands out. The user registers
 * `http://localhost` once, whatever port the app happens to get.
 */
object OAuthLoopback {
    /** The host part of the redirect URI. */
    const val HOST = "localhost"

    /** The redirect URI template, with the port filled in. */
    fun redirectUri(port: Int): String = "http://$HOST:$port"

    /** How long the listener waits for the user to finish signing in. */
    const val TIMEOUT_MS = 5 * 60 * 1000L
}
