package com.singularity.todo.core.auth

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.ids.UserId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("fast")
class AuthDomainTest {

    @Test
    fun `validateEmail accepts valid email`() {
        listOf("a@b.com", "test@example.org", "user.name@domain.co.uk").forEach { email ->
            AuthDomain.validateEmail(email) // Should not throw
        }
    }

    @Test
    fun `validateEmail rejects blank`() {
        assertFailsWith<AppError.Validation> { AuthDomain.validateEmail("") }
    }

    @Test
    fun `validateEmail rejects no at sign`() {
        assertFailsWith<AppError.Validation> { AuthDomain.validateEmail("notanemail") }
    }

    @Test
    fun `validateEmail rejects no domain`() {
        assertFailsWith<AppError.Validation> { AuthDomain.validateEmail("user@") }
    }

    @Test
    fun `validatePassword accepts 8+ chars`() {
        AuthDomain.validatePassword("password123") // Should not throw
    }

    @Test
    fun `validatePassword rejects too short`() {
        assertFailsWith<AppError.Validation> { AuthDomain.validatePassword("1234567") }
    }

    // ── REQ-UA-009: an address is normalised before it is judged ──────────────

    @Test
    fun `an address with padding and capital letters is accepted`() {
        // Both spellings name one mailbox, and the provider folds case, so refusing
        // this was refusing a correct address for a cosmetic reason.
        listOf("  user@mail.com  ", "User@Mail.COM", "\tUSER@MAIL.COM\n").forEach { email ->
            AuthDomain.validateEmail(email) // Should not throw
        }
    }

    @Test
    fun `normalising folds case and trims, and leaves a canonical address`() {
        assertEquals("user@mail.com", AuthDomain.normalizeEmail("  User@Mail.COM  "))
        assertEquals("user.name+tag@sub.domain.co.uk", AuthDomain.normalizeEmail("User.Name+Tag@Sub.Domain.CO.UK"))
    }

    @Test
    fun `normalising is idempotent, so a normalised address validates as itself`() {
        val once = AuthDomain.normalizeEmail("  User@Mail.COM ")
        assertEquals(once, AuthDomain.normalizeEmail(once))
        AuthDomain.validateEmail(once)
    }

    @Test
    fun `an address that is only whitespace is still blank after normalising`() {
        assertFailsWith<AppError.Validation> { AuthDomain.validateEmail("     ") }
    }

    // ── REQ-UA-010: both inputs are bounded ──────────────────────────────────

    @Test
    fun `an address at the bound is accepted and one character over is refused`() {
        val domain = "@mail.com"
        val local = "a".repeat(AuthDomain.MAX_EMAIL_LENGTH - domain.length)
        assertEquals(AuthDomain.MAX_EMAIL_LENGTH, (local + domain).length)
        AuthDomain.validateEmail(local + domain)

        val tooLong = "a".repeat(AuthDomain.MAX_EMAIL_LENGTH - domain.length + 1) + domain
        val error = assertFailsWith<AppError.Validation> { AuthDomain.validateEmail(tooLong) }
        assertEquals("auth.email.too_long", error.code)
    }

    @Test
    fun `a password at the bound is accepted and one character over is refused`() {
        AuthDomain.validatePassword("a".repeat(AuthDomain.MAX_PASSWORD_BYTES))

        val error = assertFailsWith<AppError.Validation> {
            AuthDomain.validatePassword("a".repeat(AuthDomain.MAX_PASSWORD_BYTES + 1))
        }
        assertEquals("auth.password.too_long", error.code)
    }

    @Test
    fun `an over-long password is refused before the length of the minimum is complained about`() {
        // Order matters: a 5000-character password is both too short for nothing and
        // too long for the provider, and the message has to be the one that tells the
        // user which of the two bounds it hit.
        val error = assertFailsWith<AppError.Validation> { AuthDomain.validatePassword("a".repeat(5000)) }
        assertEquals("auth.password.too_long", error.code)
    }

    @Test
    fun `the minimum password bound is the one already in force`() {
        // Guards the refactor: the new constant must not have moved the old bound.
        assertEquals(8, AuthDomain.MIN_PASSWORD_LENGTH)
        assertFailsWith<AppError.Validation> { AuthDomain.validatePassword("a".repeat(7)) }
        AuthDomain.validatePassword("a".repeat(8))
    }

    @Test
    fun `a password made of multi-byte characters is refused at the byte count, not the character count`() {
        // 30 four-byte characters is 120 bytes: over the bound, though only 30
        // characters long. The conservative direction is deliberate — a password whose
        // tail the provider silently discards is worse than one refused outright.
        val error = assertFailsWith<AppError.Validation> { AuthDomain.validatePassword("🔒".repeat(30)) }
        assertEquals("auth.password.too_long", error.code)
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
