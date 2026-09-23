package com.singularity.todo.core.auth.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Parses an OIDC ID Token (JWT) and exposes standard claims.
 *
 * Does **not** verify the token signature — signature verification requires
 * the provider's JWKS endpoint and is implementation-specific.
 *
 * @param jwt The raw JWT string (three dot-separated Base64URL segments).
 */
class IdToken(jwt: String) {
    private val payload: JsonObject

    init {
        val parts = jwt.split(".")
        val payloadBase64 = parts.getOrElse(1) { throw IllegalArgumentException("Invalid JWT: expected 3 parts") }
        val padded = padBase64(payloadBase64)
        val decoded = Base64.UrlSafe.decode(padded).decodeToString()
        payload = Json.parseToJsonElement(decoded) as JsonObject
    }

    /** The `email` claim, or `null` if absent or blank. */
    val email: String?
        get() = payload["email"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    /** The `sub` (subject / user ID) claim. */
    val sub: String?
        get() = payload["sub"]?.jsonPrimitive?.content

    /** The `preferred_username` claim (OIDC standard). */
    val preferredUsername: String?
        get() = payload["preferred_username"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    /** The `login` claim (GitHub-style). */
    val login: String?
        get() = payload["login"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private companion object {
        fun padBase64(input: String): String {
            val remainder = input.length % 4
            return if (remainder == 0) input else input + "=".repeat(4 - remainder)
        }
    }
}
