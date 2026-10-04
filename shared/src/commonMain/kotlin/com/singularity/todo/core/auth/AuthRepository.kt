package com.singularity.todo.core.auth

import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.log.Redaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The repository interface for authentication.
 *
 * ## What the interface deliberately does not have
 *
 * No `refresh()`, no `isAnonymous` accessor, and no way to ask the repository to
 * change identity behind a screen's back. Everything a screen needs is a verb that
 * changes the session and a [currentSession] to render. The machinery — refreshing
 * an expired token, deciding whether a failure is recoverable — belongs to the
 * implementation, because a caller that could pass `force = true` would eventually
 * pass it, and the one thing that must not be optional is giving up on a session
 * that is still recoverable.
 */
interface AuthRepository {
    val currentSession: StateFlow<Session>
    val isLoading: StateFlow<Boolean>
    suspend fun signUp(email: String, password: String): Result<Unit>
    suspend fun signIn(email: String, password: String): Result<Unit>
    suspend fun signInAnonymously(): Result<Unit>
    suspend fun signOut(): Result<Unit>

    /**
     * Attaches an account to the current anonymous session, keeping its data.
     *
     * Takes the credentials rather than an expected id. An earlier shape took the
     * id the caller believed the session would end up with, which left the
     * repository with nothing to attach and a standing temptation to pass a
     * placeholder address — which the provider would have accepted, creating a
     * real account at a domain nobody owns.
     */
    suspend fun migrateAnonymousTo(email: String, password: String): Result<Unit>
}

/**
 * [AuthRepository] over an [AuthGateway].
 *
 * ## The four rules this class exists to keep
 *
 * **The pending flag clears on every path.** REQ-UA-007. Validation failure,
 * network failure, a session the provider forgot to create — all of them leave
 * `_isLoading` false. It is cleared in a `finally`, because a spinner that
 * outlives its operation is indistinguishable from a hang.
 *
 * **A failed sign-out still signs out locally.** The user asked to be signed out
 * and the outcome they asked for does not depend on the server agreeing. If the
 * network call fails, the credentials are cleared anyway; the tokens stop being
 * valid server-side when the device next reaches the network. Holding the session
 * because a request failed would mean the user cannot sign out on a plane.
 *
 * **Queued changes are never touched.** REQ-UA-006. Nothing in this class writes
 * to the outbox, on any path — including sign-out, and including a failed
 * transfer. The outbox is the user's unsent work; it survives being signed out and
 * is delivered after the next sign-in.
 *
 * **A transport failure is not a verdict on the token.** REQ-UA-003. A refresh
 * that fails because the device is offline leaves the stored session alone so the
 * next launch can try again. Only a refresh the server *rejects* — the gateway
 * answering null — signs the user out, because that is the one answer that says
 * the token is dead rather than the request being.
 */
class SupabaseAuthRepository(
    private val log: Logger,
    private val gateway: AuthGateway,
    private val sessionStore: SecureSessionStore,
    private val scope: com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope,
) : AuthRepository {

    private val _currentSession = MutableStateFlow<Session>(Session.Loading)
    override val currentSession: StateFlow<Session> = _currentSession.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        scope.launch { restoreSession() }
    }

    /**
     * Restores a stored session, refreshing it if the access token has expired.
     *
     * The three values are read through one call each and used together, and the
     * id is stored rather than decoded from the token: a JWT's subject would do,
     * but on this path the token may be expired and unverifiable, and the sync
     * scope provider downstream treats whatever it is given as the owner of every
     * row it writes.
     */
    private suspend fun restoreSession() {
        val refreshToken = sessionStore.currentRefreshToken()
        val userId = sessionStore.currentUserId()
        val email = sessionStore.currentEmail()
        if (refreshToken == null || userId == null) {
            _currentSession.value = Session.SignedOut
            return
        }
        val accessToken = sessionStore.currentAccessToken().orEmpty()

        gateway.refresh(accessToken, refreshToken)
            .onSuccess { session ->
                if (session == null) {
                    // The server refused the refresh token. Keeping it would mean
                    // every later sync fails with a 401 nothing ever clears.
                    log.w { "The stored session was rejected; signing out" }
                    sessionStore.clear()
                    _currentSession.value = Session.SignedOut
                } else {
                    apply(session, session.email ?: email.orEmpty())
                }
            }
            .onFailure {
                log.w(it) { "Could not refresh the stored session; keeping it for the next attempt" }
                _currentSession.value = Session.SignedIn(
                    UserId.fromString(userId),
                    email.orEmpty(),
                    accessToken,
                    refreshToken,
                )
            }
    }

    override suspend fun signUp(email: String, password: String): Result<Unit> = attempt("sign up", email) {
        AuthDomain.validateEmail(email)
        AuthDomain.validatePassword(password)
        gateway.signUp(email, password)
    }

    override suspend fun signIn(email: String, password: String): Result<Unit> = attempt("sign in", email) {
        AuthDomain.validateEmail(email)
        AuthDomain.validatePassword(password)
        gateway.signIn(email, password)
    }

    override suspend fun signInAnonymously(): Result<Unit> = attempt("anonymous sign in", null) {
        gateway.signInAnonymously()
    }

    /**
     * Runs one attempt with the pending flag set and cleared around it.
     *
     * A sign-up that produces a session with no address is reported as a **success**
     * with a signed-out session, and the two together are the whole message: the
     * account was created, and it cannot be used until the address is confirmed.
     *
     * Failure would be wrong in a way that costs the user something: the account
     * exists, so a retry answers "that address is already registered" and the user
     * is left believing the sign-up did not happen. Success with a signed-out
     * session tells the screen to say "check your mail", which is the truth, and
     * nothing is persisted because there is nothing to persist.
     */
    private suspend fun attempt(
        what: String,
        email: String?,
        block: suspend () -> Result<RemoteSession>,
    ): Result<Unit> {
        _isLoading.value = true
        return try {
            // `getOrThrow` re-raises the gateway's own failure, and
            // `runCatchingResult` passes an AppError through untouched, so the
            // provider's wording survives instead of being flattened into
            // "unknown" on the way out.
            runCatchingResult { block().getOrThrow() }
                .onSuccess { remote ->
                    if (remote.email.isNullOrBlank() && !remote.isAnonymous) {
                        _currentSession.value = Session.SignedOut
                    } else {
                        apply(remote, remote.email.orEmpty())
                    }
                }
                .map { }
                .onFailure { e ->
                    log.e(e) { "Auth $what failed [email=${email?.let(Redaction::redactEmail) ?: "none"}]" }
                }
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Publishes a session, writing it to the store first.
     *
     * Order matters: a collector that reacts to the new session by reading a token
     * must find the token already there, or the first request it makes goes out
     * unauthenticated.
     */
    private suspend fun apply(remote: RemoteSession, email: String) {
        if (remote.isAnonymous) {
            _currentSession.value = Session.Anonymous(UserId.fromString(remote.userId))
        } else {
            val session = Session.SignedIn(
                userId = UserId.fromString(remote.userId),
                email = email,
                accessToken = remote.accessToken,
                refreshToken = remote.refreshToken,
            )
            sessionStore.save(session)
            _currentSession.value = session
        }
    }

    override suspend fun signOut(): Result<Unit> {
        _isLoading.value = true
        return try {
            val result = runCatchingResult {
                sessionStore.clear()
                _currentSession.value = Session.SignedOut
            }
            // After the local state, not before: a server that is unreachable is
            // not a reason to keep credentials the user asked to be rid of.
            gateway.signOut().onFailure {
                log.w(it) { "Sign out could not reach the server; the session is cleared locally anyway" }
            }
            result
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Attaches an account to an anonymous session, keeping its data.
     *
     * ## Why nothing is transferred
     *
     * REQ-UA-005 asks that an anonymous user's existing tasks end up belonging to
     * the account they then sign up with, and that the move be all-or-nothing.
     * Neither is achieved by moving rows, and attempting it would be the fragile
     * option: the provider attaches the credentials to the identity that already
     * exists, so the user id — the owner every synchronised row is scoped to —
     * does not change, and the rows are already the new account's. A transfer would
     * be a second, redundant operation with its own failure mode, and the only
     * outcome in which it is right is the one in which there is nothing to do.
     *
     * ## Why the id is checked anyway
     *
     * The argument above is a property of the provider, not of this code. A
     * provider that issued a *different* id would orphan every row the anonymous
     * user owns: local data under the old owner, server data under the new one,
     * and no error from either side. So the id is compared, and a change is
     * reported as the failure it is rather than adopted as a successful sign-up.
     *
     * On that path the session is left as the provider left it and the user is
     * told the data is not reachable. Restoring the anonymous session and reporting
     * failure is the honest pairing: the alternative is success, and a success that
     * hides orphaned data until the user goes looking for it.
     */
    override suspend fun migrateAnonymousTo(email: String, password: String): Result<Unit> {
        val before = (_currentSession.value as? Session.Anonymous)?.userId
            ?: return Result.failure(
                AppError.Validation(
                    "Only an anonymous session can be attached to an account",
                    code = "auth.not_anonymous",
                ),
            )
        return runCatchingResult {
            AuthDomain.validateEmail(email)
            AuthDomain.validatePassword(password)
        }.mapCatching {
            attempt("attach identity", email) { gateway.attachEmail(email, password) }
        }.getOrElse { Result.failure(it) }
            .mapCatching {
                // A successful attach is no longer anonymous — it is a signed-in
                // session. What has to be compared is the owner, not the shape.
                val after = _currentSession.value.ownerOrNull()
                    ?: throw AppError.Unauthorized(
                        "Attaching an account left no session behind",
                        code = "auth.ownership_changed",
                    )
                if (after != before) {
                    throw AppError.Unauthorized(
                        "Attaching an account changed the owner from $before to $after, so data " +
                            "owned by $before would no longer be reachable. The change is reported " +
                            "rather than adopted.",
                        code = "auth.ownership_changed",
                    )
                }
            }
    }
}

/** The owner this session acts as, whether it is claimed or not. */
private fun Session.ownerOrNull(): UserId? = when (this) {
    is Session.Anonymous -> userId
    is Session.SignedIn -> userId
    Session.Loading, Session.SignedOut -> null
}
