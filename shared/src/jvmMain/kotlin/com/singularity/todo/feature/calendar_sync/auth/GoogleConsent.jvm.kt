package com.singularity.todo.feature.calendar_sync.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.singularity.todo.core.auth.oauth.OAuthBrowser
import com.singularity.todo.core.auth.oauth.OAuthConsentFlow
import com.singularity.todo.core.auth.oauth.OAuthRedirectSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * The Google consent on the desktop.
 *
 * ## Why the desktop still uses a loopback redirect
 *
 * Google's deprecation of the loopback flow covers Android, iOS and Chrome clients —
 * the desktop is explicitly exempt, and the desktop app is where the loopback was
 * designed for: the browser runs on the same machine as the app.
 *
 * ## Why the code can be redeemed here
 *
 * A Desktop OAuth client is a public client. Google lets it exchange the code
 * without a client secret, and lets it renew the refresh token the same way — so the
 * refresh token can live in the user's own keystore, and no server is involved.
 */
@Composable
actual fun rememberGoogleConsent(clientId: String?): GoogleConsent {
    val scope = rememberCoroutineScope()
    // From the graph, not constructed here: the browser and the socket are platform
    // pieces with their own bindings, and a second copy of either would be a second
    // opinion about how the redirect comes back.
    val browser = koinInject<OAuthBrowser>()
    val socket = koinInject<OAuthRedirectSocket>()
    return remember {
        JvmGoogleConsent(browser = browser, socket = socket, scope = scope)
    }
}

/**
 * The consent, as the settings screen drives it.
 */
private class JvmGoogleConsent(
    private val browser: OAuthBrowser,
    private val socket: OAuthRedirectSocket,
    private val scope: CoroutineScope,
) : GoogleConsent {

    override val isSupported: Boolean = true

    override fun authorize(
        clientId: String,
        scopes: List<String>,
        expectedState: String,
        buildUrl: (redirectUri: String) -> String,
        onResult: (Result<GoogleGrant>) -> Unit,
    ) {
        scope.launch {
            val flow = OAuthConsentFlow(browser, socket)
            val result = flow.authorize(
                buildUrl = buildUrl,
                expectedState = expectedState,
            )
            onResult(
                result.map { grant ->
                    GoogleGrant.AuthorizationCode(code = grant.code, redirectUri = grant.redirectUri)
                },
            )
        }
    }
}
