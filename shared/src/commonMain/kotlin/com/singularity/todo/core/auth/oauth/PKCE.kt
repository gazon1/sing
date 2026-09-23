package com.singularity.todo.core.auth.oauth

import okio.Buffer

/**
 * PKCE (Proof Key for Code Exchange — RFC 7636) helper.
 *
 * Use [generateVerifier] to create a cryptographically random code verifier,
 * then [generateChallenge] to derive the corresponding code challenge for the
 * authorization URL.
 *
 * Example flow:
 * ```
 * val verifier = PKCE.generateVerifier()
 * val challenge = PKCE.generateChallenge(verifier)
 * val authUrl = client.buildAuthUrl(config.copy(codeChallenge = challenge), ...)
 * // Store verifier securely, use it when exchanging the code for tokens.
 * ```
 */
object PKCE {
    /**
     * Generates a new PKCE code verifier.
     *
     * 32 random bytes, Base64URL-encoded with padding removed.
     * Length: 43 characters. Entropy: 256 bits.
     */
    fun generateVerifier(): String {
        val bytes = secureRandomBytes(32)
        return bytes.toByteString().base64Url().trimEnd('=')
    }

    /**
     * Generates a PKCE code challenge from a verifier.
     *
     * SHA-256 hash of the verifier, Base64URL-encoded with padding removed.
     * Must be paired with `codeChallengeMethod = "S256"` in the authorization URL.
     */
    fun generateChallenge(verifier: String): String {
        val digest = Buffer().writeUtf8(verifier).sha256()
        return digest.base64Url().trimEnd('=')
    }
}

/** Generates cryptographically secure random bytes. */
internal expect fun secureRandomBytes(count: Int): ByteArray

@PublishedApi
internal fun ByteArray.toByteString(): okio.ByteString = okio.ByteString.of(*this)
