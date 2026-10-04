package com.singularity.todo.core.auth.oauth

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

@Tag("fast")
class PKCETest {

    @Test
    fun `generateVerifier produces URL-safe base64 without padding`() {
        val verifier = PKCE.generateVerifier()
        // 32 bytes → 43 chars base64url (no padding)
        assertEquals(43, verifier.length)
        assertFalse(verifier.contains("="), "verifier should have no padding")
        assertFalse(verifier.contains("+"), "verifier should be URL-safe")
        assertFalse(verifier.contains("/"), "verifier should be URL-safe")
    }

    @Test
    fun `generateVerifier is different each call`() {
        val v1 = PKCE.generateVerifier()
        val v2 = PKCE.generateVerifier()
        assertNotEquals(v1, v2, "each call should produce a unique verifier")
    }

    @Test
    fun `generateChallenge is deterministic for same verifier`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val c1 = PKCE.generateChallenge(verifier)
        val c2 = PKCE.generateChallenge(verifier)
        assertEquals(c1, c2, "same verifier must produce same challenge")
    }

    @Test
    fun `generateChallenge is URL-safe base64`() {
        val verifier = PKCE.generateVerifier()
        val challenge = PKCE.generateChallenge(verifier)
        assertFalse(challenge.contains("="), "challenge should have no padding")
        assertFalse(challenge.contains("+"), "challenge should be URL-safe")
        assertFalse(challenge.contains("/"), "challenge should be URL-safe")
    }

    @Test
    fun `challenge differs from verifier`() {
        val verifier = PKCE.generateVerifier()
        val challenge = PKCE.generateChallenge(verifier)
        assertNotEquals(verifier, challenge, "challenge should differ from verifier")
    }
}
