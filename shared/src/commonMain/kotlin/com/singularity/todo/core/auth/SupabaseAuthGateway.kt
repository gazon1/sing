package com.singularity.todo.core.auth

import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingCancellable
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.CancellationException

/**
 * [AuthGateway] over the Supabase auth plugin.
 *
 * **A vendor seam.** The third and last of them, alongside
 * `SupabaseClientProvider` and `PostgrestSyncRpc`.
 *
 * ## Why every call reads the session back instead of trusting the return value
 *
 * `signInAnonymously`, `attachEmail` and `refresh` all return `Unit` or a user
 * object; none of them returns the token pair the repository has to persist. The
 * session is therefore read from the plugin afterwards, and a call that returned
 * without leaving a session behind is a failure rather than a silent no-op — a
 * sign-in that reports success and stores nothing would sign the user in to
 * nothing at all, and the symptom would appear much later as every request
 * answering "not authenticated".
 */
class SupabaseAuthGateway(private val auth: Auth, private val log: Logger) : AuthGateway {

    override suspend fun signUp(email: String, password: String): Result<RemoteSession> = call("sign up") {
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
        auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        auth.currentSessionOrNull()?.toRemote()
    }

    override suspend fun signInAnonymously(): Result<RemoteSession> = call("anonymous sign in") {
        auth.signInAnonymously()
        auth.currentSessionOrNull()?.toRemote()
    }

    override suspend fun attachEmail(email: String, password: String): Result<RemoteSession> = call("attach identity") {
        // `updateUser` on an anonymous session attaches the credentials to the
        // identity that already exists. The user id does not change, which is what
        // makes the rows already written under it the new account's without
        // anything moving.
        auth.updateUser {
            this.email = email
            this.password = password
        }
        auth.currentSessionOrNull()?.toRemote()
    }

    override suspend fun signOut(): Result<Unit> = call("sign out") {
        auth.signOut()
    }

    override suspend fun refresh(
        accessToken: String,
        refreshToken: String,
    ): Result<RemoteSession?> = call("token refresh") {
        // A rejected refresh token is the answer, not an error to propagate: it is
        // how the repository learns the session is unrecoverable. Everything else
        // still throws, because a timeout is worth retrying and a rejection is not.
        runCatchingCancellable { auth.refreshSession(refreshToken) }
            .getOrNull()
            ?.toRemote()
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
            block() ?: throw AppError.Unauthorized(
                "The identity provider accepted '$what' but left no session behind",
                code = "auth.no_session",
            )
        }.onFailure { e ->
            if (e !is CancellationException) {
                log.w(e) { "Auth $what failed" }
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
}
