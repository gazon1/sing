package com.singularity.todo.core.auth.oauth

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("fast")
class IdTokenTest {

    // Real JWT structure: header.payload.signature
    // Payload: { "sub": "uid123", "email": "a@b.com", "preferred_username": "alice" }
    // Use a fake but structurally valid JWT for testing.
    // Payload base64url: eyJzdWIiOiJ1aWQxMjMiLCJlbWFpbCI6ImFAYi5jb20iLCJwcmVmZXJyZWRfdXNlcm5hbWUiOiJhbGljZSJ9

    private val validPayload = "eyJzdWIiOiJ1aWQxMjMiLCJlbWFpbCI6ImFAYi5jb20iLCJwcmVmZXJyZWRfdXNlcm5hbWUiOiJhbGljZSIsImxvZ2luIjoiYWxpY2UifQ"

    private fun jwt(payload: String = validPayload) = "eyJhbGciOiJIUzI1NiJ9.$payload.ignored_signature"

    @Test
    fun `parses email from JWT payload`() {
        val idToken = IdToken(jwt())
        assertEquals("a@b.com", idToken.email)
    }

    @Test
    fun `parses sub from JWT payload`() {
        val idToken = IdToken(jwt())
        assertEquals("uid123", idToken.sub)
    }

    @Test
    fun `parses preferredUsername from JWT payload`() {
        val idToken = IdToken(jwt())
        assertEquals("alice", idToken.preferredUsername)
    }

    @Test
    fun `parses login from JWT payload`() {
        val idToken = IdToken(jwt())
        assertEquals("alice", idToken.login)
    }

    @Test
    fun `missing fields return null`() {
        val noEmailPayload = "eyJzdWIiOiJ1aWQxMjMifQ"
        val idToken = IdToken("eyJhbGciOiJIUzI1NiJ9.$noEmailPayload.ignored")
        assertEquals(null, idToken.email)
        assertEquals("uid123", idToken.sub)
    }

    @Test
    fun `blank email returns null`() {
        val blankEmailPayload = "eyJzdWIiOiJ1aWQxMjMiLCJlbWFpbCI6IiJ9"
        val idToken = IdToken("eyJhbGciOiJIUzI1NiJ9.$blankEmailPayload.ignored")
        assertEquals(null, idToken.email)
    }
}
