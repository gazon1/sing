package com.singularity.todo.feature.calendarSync.auth

import androidx.compose.runtime.Composable

/**
 * The consent, on the platform this build targets.
 *
 * ## Why this is a Composable
 *
 * The consent is launched by the platform: the credential chooser and the authorization
 * sheet are activities, and an activity result has to come back through an
 * `ActivityResultLauncher` — which Compose creates inside a composition. The project's
 * house pattern for exactly this is `rememberCalendarPermissionRequester`: a
 * `@Composable` factory in common code with one actual per platform, and the screen
 * owns the launcher while the ViewModel owns the state.
 *
 * ## What comes back
 *
 * Two different things, because the platforms give two different things:
 *
 * - [GoogleGrant.AuthorizationCode] — a code the app redeems itself. Desktop can do
 *   this: a Desktop OAuth client is a public client, so the exchange needs no secret.
 * - [GoogleGrant.ServerAuthCode] — a code the *server* redeems. On Android this is the
 *   only kind there is. Google's offline flow hands the app a one-time code and keeps
 *   the refresh token behind it; only a holder of the client secret can redeem it,
 *   and a client secret inside an APK is a secret that has leaked.
 *
 * @param clientId the OAuth client id to authorize against, or null when none is
 *   configured. A platform that cannot ask for a grant without one reports
 *   [GoogleConsent.isSupported] as false rather than failing at the moment the user
 *   presses the button.
 */
@Composable
expect fun rememberGoogleConsent(clientId: String?): GoogleConsent

/**
 * The consent step: asks the user to allow the scopes, and reports what came back.
 */
interface GoogleConsent {
    /**
     * Whether this platform can ask for a Google grant at all.
     *
     * A platform that cannot must say so rather than pretend: a "Connect" button that
     * fails after the user pressed it is worse than a disabled one with a reason.
     */
    val isSupported: Boolean

    /**
     * Starts the consent flow.
     *
     * @param clientId the OAuth client id to authorize against.
     * @param scopes the scopes to ask for.
     * @param expectedState the request's fingerprint; a redirect that does not echo it
     *   is not an answer to this request.
     * @param buildUrl builds the consent URL for the platforms that send the user to a
     *   browser. Ignored where the platform builds the request itself.
     * @param onResult the grant, or why there is none. Called exactly once.
     */
    fun authorize(
        clientId: String,
        scopes: List<String>,
        expectedState: String,
        buildUrl: (redirectUri: String) -> String,
        onResult: (Result<GoogleGrant>) -> Unit,
    )
}

/**
 * What the consent produced.
 */
sealed interface GoogleGrant {
    /**
     * An authorization code the app redeems itself.
     *
     * @param redirectUri the address the code came back to. The exchange has to present
     *   the same one; Google rejects it otherwise, with a message that does not name
     *   the mismatch.
     */
    data class AuthorizationCode(val code: String, val redirectUri: String) : GoogleGrant

    /**
     * A one-time code the server redeems.
     *
     * Google's offline flow calls it a *server auth code*: it is exchanged for an
     * access token and a refresh token by whoever holds the client secret.
     */
    data class ServerAuthCode(val code: String) : GoogleGrant
}
