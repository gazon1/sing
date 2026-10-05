package com.singularity.todo.core.auth

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.ids.UserId

/**
 * Pure domain logic for authentication — no dependencies.
 * Fully testable without mocks.
 */
object AuthDomain {
    private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    /**
     * Stable grouping codes, named so the call sites below stay readable — the literal is
     * what the crash dashboard groups by, and it must not be retyped.
     */
    private const val EMAIL_BLANK = "auth.email.blank"
    private const val EMAIL_MALFORMED = "auth.email.malformed"
    private const val EMAIL_TOO_LONG = "auth.email.too_long"
    private const val PASSWORD_TOO_SHORT = "auth.password.too_short"
    private const val PASSWORD_TOO_LONG = "auth.password.too_long"

    /**
     * The accepted length of an address, in characters.
     *
     * RFC 5321 caps a whole address at 254 characters. The bound is here rather than
     * only on the server so a paste of a megabyte-long string is refused before it
     * becomes a request, and so the user is told the address was refused rather than
     * receiving whatever the provider chooses to say about it.
     */
    const val MAX_EMAIL_LENGTH = 254

    /**
     * The accepted length of a password, in bytes of its UTF-8 encoding.
     *
     * Sixty-four is the largest value the credential hash behind the provider accepts
     * without silently ignoring the rest, and a longer password is not a stronger one
     * — it is a longer one whose tail is discarded. Refusing it says so; accepting it
     * would let a user believe a passphrase was stronger than it is.
     */
    const val MAX_PASSWORD_BYTES = 72

    /** The smallest accepted password length, in characters. */
    const val MIN_PASSWORD_LENGTH = 8

    /**
     * The address as the provider will see it: surrounding whitespace removed and
     * letter case folded.
     *
     * Folding is what the provider does, so this is not a second opinion — it is the
     * same rule applied early, which is what makes `" user@Mail.COM "` reach the same
     * mailbox as `user@mail.com` instead of being refused as malformed. The local part
     * is case-sensitive in principle, which is why the fold matches the provider
     * rather than trying to be cleverer than it: a client that folded differently
     * would address a different mailbox than the one the account was made with.
     */
    fun normalizeEmail(email: String): String = email.trim().lowercase()

    /**
     * @throws AppError.Validation if the email is blank, malformed, or too long
     */
    fun validateEmail(email: String) {
        val normalized = normalizeEmail(email)
        if (normalized.isBlank()) reject(EMAIL_BLANK, "Email cannot be blank")
        if (normalized.length > MAX_EMAIL_LENGTH) {
            reject(
                EMAIL_TOO_LONG,
                "Email is too long (${normalized.length} characters, at most $MAX_EMAIL_LENGTH)",
            )
        }
        if (!EMAIL_REGEX.matches(normalized)) {
            reject(EMAIL_MALFORMED, "Invalid email format")
        }
    }

    /**
     * The one place an address is refused.
     *
     * Three distinct rejections with three distinct codes, because "too long" and
     * "malformed" send the user to different places — one to shorten the address,
     * the other to retype it — and a single "invalid email" would send both to the
     * same guess. `Nothing` because every branch above it is an exit.
     */
    private fun reject(code: String, message: String): Nothing =
        throw AppError.Validation(message, code = code)

    /**
     * @throws AppError.Validation if the password is shorter than the minimum or
     *   longer than the maximum
     */
    fun validatePassword(password: String) {
        if (password.length < MIN_PASSWORD_LENGTH) {
            throw AppError.Validation(
                "Password must be at least $MIN_PASSWORD_LENGTH characters",
                code = PASSWORD_TOO_SHORT,
            )
        }
        // Measured in UTF-8 bytes rather than characters, because the bound is a byte
        // bound: 30 characters of emoji is 120 bytes and is over it, and refusing that
        // is correct. Counting characters would let it through and the tail would be
        // discarded by the hash, so the user would have a shorter effective passphrase
        // than they typed. The conservative direction — refusing a password the
        // provider would have accepted — is the recoverable one.
        val bytes = password.encodeToByteArray().size
        if (bytes > MAX_PASSWORD_BYTES) {
            throw AppError.Validation(
                "Password is too long ($bytes bytes, at most $MAX_PASSWORD_BYTES)",
                code = PASSWORD_TOO_LONG,
            )
        }
    }

    /**
     * Returns the effective userId for the current session.
     * For SignedIn and Anonymous — returns their userId.
     * For Loading and SignedOut — returns anonymous.
     */
    fun effectiveUserId(session: Session): UserId = when (session) {
        is Session.SignedIn -> session.userId
        is Session.Anonymous -> session.userId
        Session.Loading, Session.SignedOut -> UserId.anonymous
    }

    /**
     * Returns true if the session requires authentication (i.e., user must sign in).
     */
    fun isAuthRequired(session: Session): Boolean = session is Session.SignedOut
}
