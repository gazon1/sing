package com.singularity.todo.core.auth

import kotlinx.coroutines.flow.Flow

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

    /**
     * The live session, or null when the device holds none.
     *
     * ## Why the session is read in one piece
     *
     * The repository used to read the access token, the refresh token, the owner id
     * and the address out of the credential store separately, and reconcile them. They
     * can disagree — a write that failed half way leaves exactly that — and a session
     * assembled from disagreeing pieces is a session nobody can use: the owner the
     * rows would be filed under is not the owner the tokens belong to.
     *
     * ## Why a failure is an exception and not a null
     *
     * Null means "this device holds no session", which is an ordinary state. A store
     * that cannot be read is a different thing, and the repository has to be able to
     * tell them apart: the first signs the user out, the second signs the user out
     * *and says so*. A port that cannot express the difference cannot carry it.
     */
    suspend fun currentSession(): RemoteSession?

    /**
     * The provider's session, as it changes.
     *
     * The provider owns the session, and it may replace it: a refresh it did on its
     * own, a session it imported from the store, a token the provider rotated behind
     * the application's back. The application has to hear about the replacement, or it
     * keeps publishing tokens the provider has already retired, and every request it
     * makes afterwards is made as somebody whose session ended.
     *
     * A null is an answer and not a failure: "the provider holds no session" is what a
     * signed-out device says. The port says nothing about *why* there is none — the
     * repository's machine has no transition for a session that ended on its own, and
     * it must not invent one.
     *
     * The flow is cold and lives as long as it is collected. It says nothing about
     * when the provider changes the session, only that it did.
     */
    fun sessionChanges(): Flow<RemoteSession?>
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
