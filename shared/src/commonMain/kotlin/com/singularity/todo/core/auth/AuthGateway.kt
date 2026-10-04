package com.singularity.todo.core.auth

/**
 * The identity operations the repository needs, in the project's own vocabulary.
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

    /**
     * Creates a real provider-side anonymous identity.
     *
     * REQ-UA-004: this has to be an identity the backend recognises, not a locally
     * generated placeholder, or the user's data would belong to something the
     * server has never heard of and could never be claimed later.
     */
    suspend fun signInAnonymously(): Result<RemoteSession>

    /**
     * Attaches an email and password to the *current* session, keeping its
     * identity.
     *
     * This is what makes an anonymous user's data belong to their new account
     * without anything moving: the identity does not change, so the rows already
     * written under it are already the new account's. See
     * [SupabaseAuthRepository.migrateAnonymousTo] for why the id is checked
     * afterwards anyway.
     */
    suspend fun attachEmail(email: String, password: String): Result<RemoteSession>

    /** Ends the session provider-side. */
    suspend fun signOut(): Result<Unit>

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
 * [userId] is the owner every synchronised row is scoped to. [isAnonymous] is not
 * a presentation detail: an anonymous identity is one the user has not yet claimed
 * and may still sign up from, and the two need different behaviour on sign-out.
 */
data class RemoteSession(
    val userId: String,
    val email: String?,
    val accessToken: String,
    val refreshToken: String,
    val isAnonymous: Boolean,
)
