package com.singularity.todo.core.auth

import com.singularity.todo.core.error.AppError
import com.singularity.todo.feature.tasks.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthDomainTest {

    @Test
    fun `validateEmail accepts valid email`() {
        listOf("a@b.com", "test@example.org", "user.name@domain.co.uk").forEach { email ->
            AuthDomain.validateEmail(email) // Should not throw
        }
    }

    @Test(expected = AppError.Validation::class)
    fun `validateEmail rejects blank`() {
        AuthDomain.validateEmail("")
    }

    @Test(expected = AppError.Validation::class)
    fun `validateEmail rejects no at sign`() {
        AuthDomain.validateEmail("notanemail")
    }

    @Test(expected = AppError.Validation::class)
    fun `validateEmail rejects no domain`() {
        AuthDomain.validateEmail("user@")
    }

    @Test
    fun `validatePassword accepts 8+ chars`() {
        AuthDomain.validatePassword("password123") // Should not throw
    }

    @Test(expected = AppError.Validation::class)
    fun `validatePassword rejects too short`() {
        AuthDomain.validatePassword("1234567")
    }

    @Test
    fun `effectiveUserId returns correct id for each session type`() {
        val userId = UserId.generate()
        assertEquals(userId, AuthDomain.effectiveUserId(Session.SignedIn(userId, "e@x.com", "t", "r")))
        assertEquals(userId, AuthDomain.effectiveUserId(Session.Anonymous(userId)))
        assertEquals(UserId.anonymous, AuthDomain.effectiveUserId(Session.Loading))
        assertEquals(UserId.anonymous, AuthDomain.effectiveUserId(Session.SignedOut))
    }

    @Test
    fun `isAuthRequired returns true only for SignedOut`() {
        val userId = UserId.generate()
        assertFalse(AuthDomain.isAuthRequired(Session.SignedIn(userId, "e@x.com", "t", "r")))
        assertFalse(AuthDomain.isAuthRequired(Session.Anonymous(userId)))
        assertFalse(AuthDomain.isAuthRequired(Session.Loading))
        assertTrue(AuthDomain.isAuthRequired(Session.SignedOut))
    }
}
