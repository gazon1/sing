package com.singularity.todo.feature.calendar_sync.auth

import com.singularity.todo.core.network.createHttpClient
import com.singularity.todo.feature.calendar_sync.error.CalendarSyncException
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder

/**
 * The half of Google OAuth that KMPAuth cannot do.
 *
 * ## Why this exists at all
 *
 * KMPAuth 3.0 obtains an `accessToken` from an interactive sign-in and stops there:
 *
 * - it requests **no `access_type=offline`**, and Google's authorization-code flow only
 *   returns a refresh token when that is explicit;
 * - it exposes **no refresh-token API** anywhere in `kmpauth-core`;
 * - its Desktop `signOut()` is a no-op that only logs.
 *
 * An access token lasts about an hour, and a calendar sync has to run unattended. Without a
 * refresh token, the first background sync after sign-in works and the next one fails, and
 * the user has to re-consent — a browser, a login, a permission screen. So the consent step
 * stays with KMPAuth (it is the part that is genuinely platform-specific and painful) and
 * the token exchange lives here, where the durable credential can be captured and stored.
 *
 * ## Why the redirect carries the code
 *
 * The authorization code arrives on a `localhost` redirect that KMPAuth's Desktop listener
 * receives. This client redeems it, with the same client id the sign-in used, so the
 * exchange needs no second registration and no PKCE verifier beyond what was sent.
 */
class GoogleTokenClient(private val httpClient: HttpClient = createHttpClient(), private val clientId: String) {

    /**
     * Redeems an authorization [code] for a durable credential.
     *
     * @param redirectUri must match the one used for the sign-in; Google rejects the
     * exchange otherwise, with a message that does not name the mismatch.
     * @param codeVerifier the PKCE verifier sent on the authorization request, when one was
     * used. Google requires it for installed apps; it is not sent by the web flow.
     */
    suspend fun exchangeCode(
        code: String,
        redirectUri: String,
        codeVerifier: String? = null,
    ): GoogleCredentials {
        val params = ParametersBuilder().apply {
            append("client_id", clientId)
            append("code", code)
            append("grant_type", "authorization_code")
            append("redirect_uri", redirectUri)
            // The one parameter that makes this grant renewable. Without it Google returns
            // an access token and no refresh token, and the account is usable for an hour.
            append("access_type", "offline")
            // Without this, a second sign-in while a grant is live silently omits the
            // refresh token — the user has consented, and gets nothing they can keep.
            append("prompt", "consent")
            codeVerifier?.let { append("code_verifier", it) }
        }.build()

        // A grant with no refresh token is still returned: it serves the foreground sync
        // that follows sign-in. `canRenew` reports that it will not last, so the caller can
        // warn before the user depends on background sync — but discarding it would make
        // the session they just authorised unusable.
        return performTokenRequest(params)
    }

    /**
     * Redeems [refreshToken] for a fresh access token.
     *
     * Google returns **no** refresh token on a refresh response — the existing one stays
     * valid — which is why the caller must carry it over. [GoogleCredentialStore] is the
     * thing that guarantees that carry-over.
     */
    suspend fun refresh(refreshToken: String): GoogleCredentials {
        val params = ParametersBuilder().apply {
            append("client_id", clientId)
            append("refresh_token", refreshToken)
            append("grant_type", "refresh_token")
        }.build()
        // Carry the existing token forward: this response has none, and dropping it would
        // mean the *next* refresh has nothing to present.
        return performTokenRequest(params).copy(refreshToken = refreshToken)
    }

    private suspend fun performTokenRequest(params: Parameters): GoogleCredentials {
        val response = httpClient.submitForm(
            url = TOKEN_ENDPOINT,
            formParameters = params,
        )
        val body = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            throw translate(response.status, body)
        }
        return parseTokenResponse(body)
            ?: throw CalendarSyncException.PermissionRevokedException(
                "Google returned a token response with no access token: $body",
            )
    }

    /**
     * Maps a token-endpoint failure onto the project's error types.
     *
     * `invalid_grant` is the one that matters: it is Google's answer to a revoked or
     * expired grant, and retrying it is guaranteed to fail. It maps to
     * [CalendarSyncException.PermissionRevokedException] so the caller stops and asks the
     * user to reconnect, instead of burning its retry budget on a dead credential.
     */
    private fun translate(status: HttpStatusCode, body: String): CalendarSyncException {
        val error = runCatching { parseTokenResponseError(body) }.getOrNull()
        return when {
            error == "invalid_grant" ->
                CalendarSyncException.PermissionRevokedException(
                    "The Google grant was revoked or has expired; the user has to reconnect",
                )

            error == "invalid_client" ->
                CalendarSyncException.PermissionRevokedException(
                    "The Google client id is not registered for this application",
                )

            status == HttpStatusCode.TooManyRequests ->
                CalendarSyncException.TransientSyncException("Google rate limit reached", null)

            status.value >= 500 ->
                CalendarSyncException.TransientSyncException("Google token endpoint is unavailable", null)

            else -> CalendarSyncException.NetworkSyncException(
                "Google returned ${status.value} from the token endpoint" +
                    (error?.let { " ($it)" } ?: ""),
                null,
            )
        }
    }

    private fun parseTokenResponseError(body: String): String? = runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(body)
        (root as? kotlinx.serialization.json.JsonObject)
            ?.get("error")
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
            ?.content
    }.getOrNull()

    companion object {
        /** Google's OAuth 2.0 token endpoint. */
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    }
}
