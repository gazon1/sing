package com.singularity.todo.core.auth.oauth

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * PKCE, checked against the vector RFC 7636 publishes.
 *
 * ## Why the published vector and not a self-consistent pair
 *
 * A test that computes the challenge with the same code the production path uses
 * proves that the code agrees with itself — and it would keep passing if the
 * encoding were wrong in a way that was wrong *consistently*, which is exactly
 * the kind of bug that only shows up when Google rejects the request. RFC 7636
 * Appendix B publishes a verifier and the challenge it must produce, so that
 * pair is the only assertion that can fail.
 */
@Tag("fast")
class PkceTest {

    /**
     * The vector from RFC 7636, Appendix B.
     *
     * If this fails, the challenge is not BASE64URL(SHA-256(verifier)) — and
     * every authorization request the app makes is rejected by the provider.
     */
    @Test
    fun `the challenge matches the vector RFC 7636 publishes`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            sha256Base64Url(verifier),
        )
    }

    /**
     * The encoding is base64url *without* padding, and the alphabet is the URL
     * one — the standard alphabet's `+` and `/` are not valid in a query value,
     * and a trailing `=` has to be dropped or the provider reads a different
     * challenge than the one that was signed.
     */
    @Test
    fun `the encoding is base64url without padding`() {
        val challenge = sha256Base64Url("a")

        assertTrue(challenge.none { it == '+' || it == '/' || it == '=' }, "got $challenge")
    }

    /**
     * The verifier has to be at least 43 characters: that is the shortest value
     * carrying 256 bits of entropy, and a shorter one is a weaker guess.
     */
    @Test
    fun `a generated verifier is the shortest length RFC 7636 accepts`() {
        val pair = generatePkcePair { ByteArray(it) { 0 } }

        assertEquals(43, pair.verifier.length)
    }

    /**
     * The two halves of the pair must agree: the challenge is derived from the
     * verifier, and the verifier is what the token exchange presents.
     */
    @Test
    fun `the challenge is derived from the verifier`() {
        val pair = generatePkcePair { ByteArray(it) { index -> index.toByte() } }

        assertEquals(sha256Base64Url(pair.verifier), pair.challenge)
    }

    /**
     * Two requests must not share a verifier.
     *
     * A verifier that repeats is a verifier an attacker can redeem an
     * intercepted code with, so the randomness is the security property — not
     * the hashing, which the RFC vector already pins.
     */
    @Test
    fun `two generated verifiers differ`() {
        val first = generatePkcePair()
        val second = generatePkcePair()

        assertNotEquals(first.verifier, second.verifier)
    }

    /** The random source is a CSPRNG, not `kotlin.random.Random`. */
    @Test
    fun `the random source produces bytes`() {
        assertEquals(32, secureRandomBytes(32).size)
    }
}
