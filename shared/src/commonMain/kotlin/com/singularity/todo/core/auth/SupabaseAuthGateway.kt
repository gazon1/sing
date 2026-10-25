package com.singularity.todo.core.auth

import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingCancellable
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException

/**
 * [AuthGateway] over the Supabase auth plugin.
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
    private suspend fun auth(): Auth = clients.client()?.auth ?: throw AuthFailure.notConfigured()

    override suspend fun signUp(email: String, password: String): Result<RemoteSession> = call("sign up") {
        val auth = auth()
        auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
        // Sign-up with email confirmation enabled leaves no session. That is the
        // server's policy, not a failure, and the caller is told so rather than
        // being handed a session that does not exist.
        auth.currentSessionOrNull()?.toRemote()
    }

    override suspend fun signIn(email: String, password: String): Result<RemoteSession> = call("sign in") {
        val auth = auth()
        auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        auth.currentSessionOrNull()?.toRemote()
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
                is io.github.jan.supabase.auth.OtpVerifyResult.Authenticated -> result.session.toRemote()

                is io.github.jan.supabase.auth.OtpVerifyResult.VerifiedNoSession ->
                    throw AuthFailure.verifiedWithoutSession()
            }
        }.onFailure { e ->
            if (e !is CancellationException) {
                log.w(e) { "Auth magic link verification failed" }
            }
        }

    override suspend fun signOut(): Result<Unit> = call("sign out") {
        // Signing out with no configured project still succeeds locally: the
        // repository clears its own credentials regardless, and failing here
        // would only stop it from doing so.
        clients.client()?.auth?.signOut()
        Unit
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
    private fun io.github.jan.supabase.auth.user.UserSession.toRemote() = RemoteSession(
        userId = user!!.id,
        email = user?.email,
        accessToken = accessToken,
        refreshToken = refreshToken,
        isAnonymous = user?.isAnonymous ?: false,
    )

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
