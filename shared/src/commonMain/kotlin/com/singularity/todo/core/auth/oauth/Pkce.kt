package com.singularity.todo.core.auth.oauth

import okio.Buffer

/**
 * A PKCE pair (RFC 7636).
 *
 * @param verifier the secret half. It never leaves the device until the token
 *   exchange, and it is what proves the authorization code came from this
 *   request and not from someone who intercepted the redirect.
 * @param challenge the public half — BASE64URL(SHA-256(verifier)) — sent with
 *   the authorization request.
 */
data class PkcePair(val verifier: String, val challenge: String)

/**
 * Cryptographically secure random bytes.
 *
 * `kotlin.random.Random` is not a CSPRNG, and a PKCE verifier has to be
 * unpredictable: a guessable verifier is a verifier an attacker can redeem the
 * intercepted code with.
 */
expect fun secureRandomBytes(size: Int): ByteArray

/**
 * Generates a PKCE pair for an authorization-code request.
 *
 * The verifier is 32 random bytes in base64url — 43 characters, the shortest
 * length RFC 7636 allows.
 */
fun generatePkcePair(randomBytes: (Int) -> ByteArray = ::secureRandomBytes): PkcePair {
    val verifier = randomBytes(VERIFIER_BYTES).base64Url()
    return PkcePair(verifier = verifier, challenge = sha256Base64Url(verifier))
}

/** BASE64URL(SHA-256(text)) without padding — the challenge encoding RFC 7636 defines. */
fun sha256Base64Url(text: String): String = Buffer().writeUtf8(text).sha256().toBase64Url()

/**
 * base64url without padding — the encoding RFC 7636 uses for both halves.
 *
 * okio's `base64()` is the standard alphabet with padding, so the two
 * characters that differ are swapped and the padding is dropped.
 *
 * `ByteString.base64Url()` would say this in one call, but it pads by default
 * and only exists in newer okio versions than this project resolves — so the
 * swap is done here, where the padding rule is visible.
 */
internal fun ByteArray.base64Url(): String = Buffer().write(this).snapshot().toBase64Url()

/** The standard base64 alphabet, made URL-safe and unpadded. */
private fun okio.ByteString.toBase64Url(): String =
    base64()
        .replace('+', '-')
        .replace('/', '_')
        .trimEnd('=')

/** 32 bytes → 43 base64url characters, the shortest verifier RFC 7636 accepts. */
private const val VERIFIER_BYTES = 32
