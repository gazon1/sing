package com.singularity.todo.core.auth

import co.touchlab.kermit.Logger
import com.mmk.kmpauth.core.auth.AuthCredential
import com.mmk.kmpauth.supabase.SupabaseAuthBackend
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingCancellable
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.OtpVerifyResult
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [AuthGateway] over Supabase, with KMPAuth's credential model in front of it.
 *
 * **A vendor seam.** The third and last of them, alongside
 * `SupabaseClientProvider` and `PostgrestSyncRpc`.
 *
 * ## What this file holds, and what it does not
 *
 * This is the seam: the vendor's plugin, the calls, and the vendor's own vocabulary
 * read where only the vendor's vocabulary can be read. The messages, the codes and
 * the rules — what "no session" means, and what a failed refresh does to the
 * session — live in [AuthFailure], where they can be read and checked without the
 * SDK.
 *
 * ## What comes from KMPAuth and what comes from the SDK
 *
 * KMPAuth's Supabase backend supplies the credential model: sign-up, sign-in,
 * sign-out and the live user. Four operations are still direct SDK calls, each
 * because KMPAuth's version of it cannot express what this application needs:
 *
 * - **the magic link.** KMPAuth's `sendSignInLinkToEmail` does not say that the
 *   account must not be created, and a magic link to an unknown address would
 *   create a ghost account.
 * - **the magic-link exchange.** KMPAuth's `signInWithEmailLink` parses a
 *   `token_hash` or a PKCE `code`; this application's link carries a plain token
 *   (`feature/auth/AuthLinkSpec.kt`).
 * - **the password change.** KMPAuth has no password change — its `reauthenticate`
 *   is a fresh sign-in.
 * - **the refresh.** KMPAuth's `currentUserIdToken` returns an access token and
 *   cannot say "the provider rejected this refresh token", which is the one answer
 *   the repository needs to tell "expired" from "unrecoverable".
 *
 *
 * ## Why every call reads the session back instead of trusting the return value
 *
 * `signUp`, `signIn`, `verifyMagicLink` and `refresh` all return a user object or
 * `Unit`; none of them returns the token pair the repository has to persist. The
 * session is therefore read from the plugin afterwards, and a call that returned
 * without leaving a session behind is a failure rather than a silent no-op — a
 * sign-in that reports success and stores nothing would sign the user in to
 * nothing at all, and the symptom would appear much later as every request
 * answering "not authenticated".
 */
class SupabaseAuthGateway(private val clients: SupabaseClientProvider, private val log: Logger) : AuthGateway {

    /**
     * The provider's auth plugin, or a clear refusal.
     *
     * Resolved per call rather than injected. A `single<Auth>` binding would have
     * to be created at graph start, when no project may be configured yet — so it
     * would either throw on first launch or be built against a value that did not
     * exist and is never refreshed. Answering "not configured" at the point of use
     * is the honest shape, and it is why this class takes the provider rather than
     * the plugin.
     */
    private suspend fun auth(): Auth = clients.auth() ?: throw AuthFailure.notConfigured()

    /**
     * KMPAuth's backend over the one client.
     *
     * Built per call rather than held: the client is rebuilt when the configured
     * project changes, and a backend that held an old client would sign the user
     * into the project they just left.
     */
    private suspend fun backend(): SupabaseAuthBackend {
        val client = clients.client()
            ?: throw AppError.Validation(
                "No Supabase project is configured, so there is nothing to sign in to.",
                code = "auth.not_configured",
            )
        return SupabaseAuthBackend(supabaseClient = client)
    }

    override suspend fun signUp(email: String, password: String): Result<RemoteSession> = call("sign up") {
        val user = backend().signUp(email, password).getOrThrow()
        val session = auth().currentSessionOrNull()
        session?.toRemote() ?: RemoteSession(
            userId = user.uid,
            // A blank address is how the repository is told that the account exists
            // but there is no session to use yet: a sign-up that needs the address
            // confirmed leaves the device signed out, and the screen has to say
            // "check your mail" rather than "that failed".
            email = "",
            accessToken = "",
            refreshToken = "",
            isAnonymous = false,
        )
    }

    override suspend fun signIn(email: String, password: String): Result<RemoteSession> = call("sign in") {
        backend().signIn(AuthCredential.EmailPassword(email = email, password = password)).getOrThrow()
        auth().currentSessionOrNull()?.toRemote()
    }

    override suspend fun updatePassword(newPassword: String): Result<Unit> = call("update password") {
        val auth = auth()
        auth.updateUser { this.password = newPassword }
        Unit
    }

    override suspend fun sendMagicLink(email: String): Result<Unit> = call("send magic link") {
        val auth = auth()
        // OTP.login sends a magic link (email OTP) to the supplied address.
        // Used for resending confirmation emails and for password-reset flows.
        auth.signInWith(OTP) {
            this.email = email
            // Do not create a new user: a magic-link resend targets an existing
            // unconfirmed account. Attempting to send to an unknown address
            // correctly surfaces the error rather than silently creating a ghost.
            createUser = false
        }
        Unit
    }

    override suspend fun verifyMagicLink(email: String, token: String): Result<RemoteSession> =
        runCatchingCancellable {
            val auth = auth()
            // Exchange the token from the magic link URL for a session.
            val result = auth.verifyEmailOtp(OtpType.Email.MAGIC_LINK, email, token)
            when (result) {
                is OtpVerifyResult.Authenticated -> result.session.toRemote()

                is OtpVerifyResult.VerifiedNoSession -> throw AuthFailure.verifiedWithoutSession()
            }
        }.onFailure { e ->
            if (e !is CancellationException) {
                log.w(e) { "Auth magic link verification failed" }
            }
        }

    override suspend fun signOut(): Result<Unit> = call("sign out") {
        val client = clients.client()
        if (client == null) {
            // Signing out with no configured project still succeeds locally: there is
            // no provider to ask, and there is nothing to end at one either.
            Unit
        } else {
            try {
                backend().signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpRequestException) {
                // The provider could not be reached. The session on *this* device must
                // still end: refusing to sign out locally because a request failed
                // would mean the user cannot sign out on a plane.
                //
                // A refusal from the provider — a status it chose — is not caught here:
                // the plugin clears the session it holds before it lets that through,
                // so the local end has already happened by the time the exception
                // reaches this catch.
                runCatchingCancellable { client.auth.clearSession() }
                    .onFailure { log.w(it) { "Could not clear the local session after a failed sign out" } }
                throw e
            }
        }
    }

    /**
     * Refreshes the session, or says that the session is gone.
     *
     * ## The two answers
     *
     * A rejected refresh token is the *answer*: the session cannot be recovered, and
     * the repository has to hear it so it can clear its credentials. Everything else
     * is a failure of the request, not a verdict on the token, and it is reported as
     * one — the stored session stays, and the next attempt tries again.
     *
     * The earlier version swallowed every failure and answered "the session is gone"
     * for all of them, which is the opposite of what the repository's contract says
     * (REQ-UA-003): a person on a train was signed out by a timeout, and a person
     * whose token was genuinely rejected kept a session that every later call
     * answered with a 401.
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun refresh(
        accessToken: String,
        refreshToken: String,
    ): Result<RemoteSession?> = runCatchingCancellable {
        val auth = auth()
        try {
            auth.refreshSession(refreshToken).toRemote()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            when (AuthFailure.refreshOutcome(failure.refreshFailureReason())) {
                RefreshOutcome.SessionIsGone -> null
                RefreshOutcome.KeepSession -> throw failure
            }
        }
    }.onFailure { e ->
        if (e !is CancellationException) {
            log.w(e) { "Auth token refresh failed" }
    /**
     * The live session, read from the credential store.
     *
     * ## Why the store is asked directly
     *
     * The plugin can load the session itself, and that is what it does when it is
     * built. It is not built here: the plugin is configured not to load at all, so
     * that the load — and the migration it triggers — happens inside the restore's
     * bound instead of inside whoever happened to ask for the client first.
     *
     * The plugin's own load path reports every failure as "no session". That is
     * right for it and wrong here: a keychain that cannot be read is not a
     * signed-out user, and the difference is the difference between the user being
     * sent to the login screen and the user being told what went wrong.
     *
     * ## Why the session is handed to the plugin afterwards
     *
     * The plugin's own requests are made as whoever it believes is signed in, and it
     * believes that only once it has been told. A plugin that has never heard of the
     * session sends the anon key — which for the backup feature means a row-level
     * security policy refusing an upload the user is entitled to make.
     */
    override suspend fun currentSession(): RemoteSession? {
        val auth = auth()
        val session = try {
            auth.sessionManager?.loadSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoSessionFoundException) {
            // The store holds no session. That is an answer, not a failure — and it is
            // the only failure this catch treats as one: a keychain that cannot be
            // read throws something else and travels on, so the caller learns that
            // the session could not be read rather than that there is none.
            log.d(e) { "The credential store holds no session" }
            null
        } ?: return null
        auth.importSession(session, autoRefresh = false, source = SessionSource.Storage)
        return session.toRemoteOrNull()
    }

    /**
     * The provider's session, as it changes.
     *
     * The plugin's own status is the answer: it is the one thing that knows what the
     * provider holds and where that session came from — a sign-in, a refresh, or the
     * store the seam handed it.
     *
     * The plugin is resolved when the flow is collected, and the observation stays with
     * the plugin it started on. A project change replaces the client, and a session
     * belongs to the client that holds it: a session observed across a project change
     * would be a session of the project the user left.
     */
    override fun sessionChanges(): Flow<RemoteSession?> = flow {
        val plugin = clients.auth()
        if (plugin == null) {
            log.d { "No Supabase project is configured, so there is no session to observe" }
            return@flow
        }
        plugin.sessionStatus.collect { status ->
            val session = (status as? SessionStatus.Authenticated)?.session
            emit(session?.toRemoteOrNull())
        }
    }

    /**
     * Runs [block], mapping a failure to [AppError] and a null session to one too.
     *
     * The two are kept distinct in the message but not in the type, because the
     * caller cannot act differently on them: both mean there is no session, and
     * both must leave the store untouched.
     */
    private suspend fun <T> call(what: String, block: suspend () -> T?): Result<T> =
        runCatchingCancellable {
            block() ?: throw AuthFailure.noSession(what)
        }.onFailure { e ->
            if (e !is CancellationException) {
                log.w(e) { "Auth $what failed" }
            }
        }

    /**
     * The vendor's answer, read as the project's question.
     *
     * The SDK says "the refresh token is not there" in its own words; the project
     * asks "is the session still recoverable?". Only the SDK's vocabulary can answer
     * the first, and only the project can say what the answer means.
     */
    private fun Throwable.refreshFailureReason(): RefreshFailureReason {
        val rest = this as? RestException ?: return RefreshFailureReason.Transient
        val code = (this as? AuthRestException)?.errorCode
        // The refresh endpoint answers 400 for a token it will not accept, and for
        // nothing else. That is the fallback for the answer that arrives without the
        // SDK's own error code.
        return when {
            code != null && code in REJECTED_REFRESH_CODES -> RefreshFailureReason.Rejected
            rest.statusCode == HTTP_BAD_REQUEST -> RefreshFailureReason.Rejected
            else -> RefreshFailureReason.Transient
        }
    }

    /**
     * Maps the provider's session onto [RemoteSession].
     *
     * Anonymous is read from the provider's own `is_anonymous` flag, not from the
     * absence of an email. Those are not the same thing: a session can have an
     * email and still be anonymous, and treating it as claimed would make the app
     * skip the sign-up path for a user who has not actually created an account.
     */
    private fun io.github.jan.supabase.auth.user.UserSession.toRemote(): RemoteSession = toRemoteOrNull()
        ?: throw AppError.Unauthorized(
            "The identity provider returned a session with no owner",
            code = "auth.no_session",
        )

    /**
     * The session, or null when it has no owner.
     *
     * A record without an owner is a state this application has devices in — a
     * token that was migrated out of plain-text preferences, where no owner id was
     * ever stored. It is not a session: there is nobody for it to be, and publishing
     * one would file the user's rows under an owner that does not exist.
     */
    private fun io.github.jan.supabase.auth.user.UserSession.toRemoteOrNull(): RemoteSession? {
        val owner = user ?: return null
        return RemoteSession(
            userId = owner.id,
            email = owner.email,
            accessToken = accessToken,
            refreshToken = refreshToken,
            isAnonymous = owner.isAnonymous ?: false,
        )
    }

    private companion object {
        /** The codes GoTrue answers with when it will not accept the refresh token. */
        val REJECTED_REFRESH_CODES = setOf(
            AuthErrorCode.RefreshTokenNotFound,
            AuthErrorCode.RefreshTokenAlreadyUsed,
            AuthErrorCode.SessionNotFound,
            AuthErrorCode.SessionExpired,
        )

        const val HTTP_BAD_REQUEST = 400
    }
}
