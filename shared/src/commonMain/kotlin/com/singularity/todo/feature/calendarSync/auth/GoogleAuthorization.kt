package com.singularity.todo.feature.calendarSync.auth

import io.ktor.http.URLBuilder

/**
 * Google's OAuth 2.0 authorization endpoint.
 *
 * The request is the authorization-code flow with PKCE: the code that comes
 * back is useless without the verifier, and the verifier never leaves the
 * device until the token exchange.
 */
object GoogleAuthorization {
    /** Where the user signs in. */
    const val ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"

    /**
     * The scopes the Calendar calls in this project need.
     *
     * One scope, the broad one: the sync reads calendars, reads changes, and
     * creates, patches and cancels events. `calendar.events` and
     * `calendar.readonly` would cover the same ground in two grants, and a
     * user who consents to one of them sees a different consent screen than
     * one who consents to the other — the split buys nothing here.
     */
    val CALENDAR_SCOPES = listOf("https://www.googleapis.com/auth/calendar")

    /**
     * Builds the URL that starts the consent step.
     *
     * @param clientId the OAuth client id — the one the user pasted, or the one
     *   the build carries.
     * @param redirectUri the loopback address the answer comes back to.
     * @param scopes the scopes to ask for.
     * @param codeChallenge the PKCE challenge.
     * @param state the request's fingerprint, echoed on the redirect.
     */
    fun buildUrl(
        clientId: String,
        redirectUri: String,
        scopes: List<String> = CALENDAR_SCOPES,
        codeChallenge: String,
        state: String,
    ): String =
        URLBuilder(ENDPOINT).apply {
            parameters.append("client_id", clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("response_type", "code")
            parameters.append("scope", scopes.joinToString(" "))
            parameters.append("code_challenge", codeChallenge)
            parameters.append("code_challenge_method", "S256")
            parameters.append("state", state)
            // The parameter that makes the grant renewable. Without it Google
            // hands back an access token and no refresh token, and the account
            // stops working after an hour — which for a background sync means
            // the next scheduled run fails with no way to recover.
            parameters.append("access_type", "offline")
            // Without this, a second consent while a grant is still live
            // silently omits the refresh token: the user has agreed, and gets
            // nothing they can keep.
            parameters.append("prompt", "consent")
        }.buildString()
}
