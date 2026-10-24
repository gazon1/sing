package com.singularity.todo.core.auth.oauth

/**
 * The loopback socket the redirect comes back to.
 *
 * ## Why this is a port
 *
 * The socket is the only part of the consent step that touches the operating
 * system. What the redirect *means* — parsing it, checking the state, telling
 * the user what went wrong — is in [OAuthConsentFlow], in common code, where a
 * test drives it without a socket at all. What is left per platform is the
 * part that only moves bytes, and that part is small enough to write twice
 * without it becoming a second copy of the logic.
 */
interface OAuthRedirectSocket {
    /**
     * Binds to a free loopback port.
     *
     * @return the port the answer will arrive on.
     */
    suspend fun bind(): Int

    /**
     * Waits for one request and returns the target it carried.
     *
     * @param timeoutMs how long to wait for the browser to come back.
     */
    suspend fun acceptRequestTarget(timeoutMs: Long): Result<String>

    /** Answers the browser, so the tab shows that the sign-in is done. */
    suspend fun respondDone()

    /** Releases the port. */
    fun close()
}
