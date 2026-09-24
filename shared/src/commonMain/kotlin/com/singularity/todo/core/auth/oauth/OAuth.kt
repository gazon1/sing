package com.singularity.todo.core.auth.oauth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Buffer

/**
 * OAuth 2.0 configuration for an authorization code flow.
 *
 * @property authorizationEndpoint  OIDC discovery endpoint for `/authorize`.
 * @property tokenEndpoint         OIDC discovery endpoint for `/token`.
 * @property clientId              Public client identifier registered with the provider.
 * @property redirectUri          Must match a URI registered with the provider.
 * @property scope                Space-separated OAuth 2.0 scopes (e.g. `"openid email profile"`).
 * @property state                Optional opaque state for CSRF protection.
 */
data class OAuthConfig(
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    val redirectUri: String,
    val scope: String,
    val state: String = "",
)

/**
 * Result of a successful OAuth 2.0 token exchange.
 *
 * @property accessToken     Bearer token for API calls.
 * @property idToken        OIDC ID Token (JWT). Parse with [IdToken].
 * @property refreshToken   Long-lived token for refreshing [accessToken].
 * @property tokenEndpoint  Endpoint used for this exchange (may differ from [OAuthConfig.tokenEndpoint]).
 * @property clientId      Client ID used for this exchange.
 * @property expiresIn      Access token lifetime in seconds (may be null if provider omits it).
 * @property grantedScopes  Space-separated scopes actually granted (may differ from requested).
 */
data class OAuthResult(
    val accessToken: String,
    val idToken: IdToken? = null,
    val refreshToken: String? = null,
    val tokenEndpoint: String? = null,
    val clientId: String? = null,
    val expiresIn: Long? = null,
    val grantedScopes: String? = null,
)

/**
 * Persistent OAuth token data — stored in [com.singularity.todo.core.auth.SessionStore].
 *
 * Stored as JSON via [serialize] / [deserialize].
 *
 * @property accessToken    Bearer token for API calls.
 * @property refreshToken   Long-lived token for refreshing.
 * @property tokenEndpoint  Refresh endpoint URL.
 * @property clientId       Client ID used when this token was acquired.
 * @property expiresAt      Expiry instant in epoch milliseconds (0 = unknown).
 */
@Serializable
data class OAuthTokenData(
    val accessToken: String,
    val refreshToken: String,
    val tokenEndpoint: String,
    val clientId: String,
    val expiresAt: Long,
) {
    /** Serializes this token to a JSON string for storage. */
    fun serialize(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true;
            encodeDefaults = true
        }

        /** Deserializes a token from a JSON string produced by [serialize]. */
        fun deserialize(data: String): OAuthTokenData = json.decodeFromString(serializer(), data)
    }
}

/** Converts an [OAuthResult] into [OAuthTokenData] for persistent storage. */
fun OAuthResult.toOAuthTokenData(refreshToken: String): OAuthTokenData = OAuthTokenData(
    accessToken = accessToken,
    refreshToken = refreshToken,
    tokenEndpoint = tokenEndpoint ?: error("No token_endpoint in OAuth result"),
    clientId = clientId ?: error("No client_id in OAuth result"),
    expiresAt = expiresIn?.let { System.currentTimeMillis() + it * 1000 } ?: 0L,
)

/** Standardized OAuth / token error string constants. */
object TokenError {
    const val REFRESH_FAILED = "Token refresh failed"
    const val EXCHANGE_FAILED = "Token exchange failed"
}

/**
 * Encodes OAuth redirect state (nonce + redirect URI) into an opaque string
 * for use as the OAuth `state` parameter.
 *
 * Format: Base64URL( JSON { "n": nonce, "r": redirectUri } )
 */
object RedirectState {
    fun encode(nonce: String, redirectUri: String): String {
        val json = JsonObject(
            mapOf(
                "n" to JsonPrimitive(nonce),
                "r" to JsonPrimitive(redirectUri),
            ),
        )
        val buffer = Buffer()
        buffer.writeUtf8(json.toString())
        return buffer.readByteString().base64()
            .replace("+", "-")
            .replace("/", "_")
            .trimEnd('=')
    }
}
