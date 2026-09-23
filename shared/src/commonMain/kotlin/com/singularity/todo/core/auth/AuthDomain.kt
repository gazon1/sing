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
     * @throws AppError.Validation if email is invalid
     */
    fun validateEmail(email: String) {
        if (email.isBlank()) throw AppError.Validation("Email cannot be blank")
        if (!EMAIL_REGEX.matches(email)) throw AppError.Validation("Invalid email format")
    }

    /**
     * @throws AppError.Validation if password is too short
     */
    fun validatePassword(password: String) {
        if (password.length < 8) throw AppError.Validation("Password must be at least 8 characters")
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
