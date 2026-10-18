package com.singularity.todo.core.auth

/**
 * The identity operations the repository needs, in the project's own vocabulary.
 *
 * ## Why there is no anonymous sign-in, and no attach
 *
 * Both were removed together, and for the same reason: the offline session is local
 * (ADR `2026-10-05-anonymous-is-local-only-and-signing-in-offers-a-choice`), so the
 * provider never issues an anonymous identity, and there is consequently no
 * provider-side session for `updateUser` to attach credentials to. Keeping either
 * method would leave a plausible-looking path to a server-side anonymous account that
 * nothing can reach — and a plausible-looking path is what the next person who reads
 * only a signature will use. The local → account move is [AuthRepository.signIn]
 * followed by a local ownership transfer, in the feature layer: see
 * `LocalAccountClaimer`.
 *
 * ## Why the SDK is behind a port
 *
 * REQ-UA-008 asks for a replaceable identity provider, and "replaceable" is not a
 * property you can verify by intending it. What makes it true is that the domain
 * sees [RemoteSession] — an id, an email, two token strings and a flag — and never
 * a `UserSession`, a `UserInfo` or a `SessionStatus`. Those carry expiry,
 * identities, provider tokens, confirmation timestamps and a source enum, none of
 * which anything in this app reads, and all of which would have to be carried
 * through every layer above if they leaked in.
 *
 * The port is also the only thing that makes the repository testable: an
 * `AuthGateway` can be told to fail, to hand back a *different* user id, or to
 * accept a session it should have rejected, and each of those is a real event the
 * repository has to survive.
 */
interface AuthGateway {

    /** Creates an account. Fails if the address is already registered. */
    suspend fun signUp(email: String, password: String): Result<RemoteSession>

    /** Signs in to an existing account. */
    suspend fun signIn(email: String, password: String): Result<RemoteSession>

    /** Ends the session provider-side. */
    suspend fun signOut(): Result<Unit>

    /**
     * Changes the password of the currently signed-in user.
     *
     * Requires a recent authentication. If the session has expired, the caller
     * should prompt re-authentication first.
     */
    suspend fun updatePassword(newPassword: String): Result<Unit>

    /**
     * Sends a one-time password (magic link) to [email].
     *
     * Used when recovering from an unconfirmed sign-up: the user receives the
     * confirmation email again and can complete sign-in from it.
     */
    suspend fun sendMagicLink(email: String): Result<Unit>

    /**
     * Verifies the one-time password [token] sent to [email].
     *
     * Completes the sign-in for an unconfirmed account: exchanges the token from
     * the magic link for a live session.
     */
    suspend fun verifyMagicLink(email: String, token: String): Result<RemoteSession>

    /**
     * Exchanges a refresh token for a new access token.
     *
     * REQ-UA-003. Returning null means the refresh token was rejected too, which
     * is the difference between "expired" and "unrecoverable" and the only reason
     * the repository can tell whether to retry or to sign the user out.
     */
    suspend fun refresh(accessToken: String, refreshToken: String): Result<RemoteSession?>
}

/**
 * A session, in the provider's own terms but the project's own types.
 *
 * [userId] is the owner every synchronised row is scoped to. [isAnonymous] is kept
 * because a provider *can* still report one — but nothing in this app creates one any
 * more, so it is now a defensive branch rather than a path. See
 * [AuthGateway] on why both provider-side operations were removed.
 */
data class RemoteSession(
    val userId: String,
    val email: String?,
    val accessToken: String,
    val refreshToken: String,
    val isAnonymous: Boolean,
)
