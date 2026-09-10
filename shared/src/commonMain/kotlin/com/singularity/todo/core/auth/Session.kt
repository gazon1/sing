package com.singularity.todo.core.auth

import com.singularity.todo.core.ids.UserId

/**
 * Represents the current authentication session.
 * Pure domain model — no platform dependencies.
 */
sealed interface Session {
    data object Loading : Session
    data object SignedOut : Session
    data class SignedIn(
        val userId: UserId,
        val email: String,
        val accessToken: String,
        val refreshToken: String
    ) : Session
    data class Anonymous(val userId: UserId) : Session
}
