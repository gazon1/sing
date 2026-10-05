package com.singularity.todo.core.auth

import com.singularity.todo.core.ids.UserId

/**
 * Represents the current authentication session.
 * Pure domain model — no platform dependencies.
 */
sealed interface Session {
    data object Loading : Session
    data object SignedOut : Session
    data class SignedIn(val userId: UserId, val email: String, val accessToken: String, val refreshToken: String) :
        Session
    data class Anonymous(val userId: UserId) : Session
}

/**
 * The account this session belongs to, or null when it belongs to none.
 *
 * "Belongs to an account" is a weaker question than "is this the same session as
 * before", and REQ-UA-018 needs the weaker one. A token refresh replaces [Session]
 * wholesale — new access token, new refresh token, same [UserId] — and it happens
 * routinely, on a timer and on any 401. Comparing whole sessions would therefore
 * discard nearly every push response, leave every outbox row queued, and re-send the
 * same patches forever under a session that differs only in a string the server
 * rotated. A response arriving after the *account* is gone has nothing to apply to;
 * a response arriving after a token rotated is the ordinary case and must be applied.
 *
 * [Session.Loading] is null: "not known yet" is not "the same account", and treating
 * it as the same would apply a response on the strength of a session that had not
 * been established.
 */
val Session.accountIdOrNull: UserId?
    get() = when (this) {
        is Session.SignedIn -> userId
        is Session.Anonymous -> userId
        Session.Loading, Session.SignedOut -> null
    }
