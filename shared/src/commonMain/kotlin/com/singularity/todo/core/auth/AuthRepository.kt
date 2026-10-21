package com.singularity.todo.core.auth

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.BoundedReadResult
import com.singularity.todo.core.coroutines.boundedRead
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.error.toAppError
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.log.Redaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
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

    /**
     * Emits once each time a restore could not be completed, and the session fell back to
     * signed out because of it.
     *
     * ## Why this is an event and not a [Session]
     *
     * The user needs to be able to tell "you are signed out" from "you were signed in and
     * this device could not read it any more". The second is a fact about an *attempt* — it
     * happened once, on this launch, and it is over. Adding it to [Session] would put a
     * third signed-out-shaped variant into every exhaustive `when (session)` in the app, all
     * of which treat it identically, and the compiler would oblige by making each of them
     * restate that identity. It is also wrong as state: after the user taps "Continue
     * Offline" the session is `Anonymous` and nothing about the failure is still true.
     *
     * An event that fires once, at the moment it is true, is the shape the situation has.
     * See REQ-UA-015.
     *
     * ## Why it has a default
     *
     * Only [SupabaseAuthRepository] restores anything; every other implementation — the dev
     * one, the instrumented stub, the fakes — is handed its session and never fails to
     * produce one. A default keeps that truth in one place instead of making a dozen
     * doubles each carry an event they can never fire.
     *
     * ## The flow completes when the restore window closes
     *
     * The report is published at most once, and only by the restore, so once the restore
     * is over this flow has nothing more to say — and it says so by completing.
     *
     * That is what makes "there was no failure" an answerable question. A reader that
     * waits for a report which will never come — `restoreFailures.firstOrNull()` in the
     * view model — waits for the restore and then learns there was no failure, instead
     * of waiting forever for an event that cannot exist. The wait is bounded by the
     * restore, and the restore is bounded by
     * [com.singularity.todo.core.coroutines.boundedRead].
     *
     * A reader that arrives *after* the window closed still learns what happened: a
     * closed channel with a buffered element delivers that element first and completes
     * afterwards.
     */
    val restoreFailures: Flow<Unit> get() = NoRestoreFailures
    suspend fun signUp(email: String, password: String): Result<Unit>
    suspend fun signIn(email: String, password: String): Result<Unit>

    /**
     * Starts a local, offline session — no account and no network.
     *
     * Named for what it does rather than for a provider's "sign in anonymously": there
     * is no provider call behind it and there never will be. The name that describes a
     * sign-in to something is the wrong name for an operation whose entire point is
     * that it reaches nothing.
     *
     * See ADR `2026-10-05-anonymous-is-local-only-and-signing-in-offers-a-choice`.
     */
    suspend fun startLocalSession(): Result<Unit>

    suspend fun signOut(): Result<Unit>

    /** Changes the password of the currently signed-in user. */
    suspend fun updatePassword(newPassword: String): Result<Unit>

    /**
     * Sends a magic link (one-time password) to [email].
     *
     * Used to recover from an unconfirmed sign-up: resends the confirmation email
     * as a magic link that, when clicked, completes sign-in.
     */
    suspend fun sendMagicLink(email: String): Result<Unit>

    /**
     * Verifies the [token] from a magic link sent to [email].
     *
     * Completes sign-in for an unconfirmed account by exchanging the token from
     * the magic link URL for a live session.
     */
    suspend fun verifyMagicLink(email: String, token: String): Result<Unit>
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
 *
 * **The local session contacts nothing.** [startLocalSession] is the whole
 * "Continue Offline" path, and there is no provider call in it — deliberately, and
 * not as a simplification. It used to sign in anonymously against the provider,
 * which made the button a network operation wearing an offline label: on a plane,
 * or on a device that has never heard of a Supabase project, it failed and the
 * user stayed on the auth screen. See ADR
 * `2026-10-05-anonymous-is-local-only-and-signing-in-offers-a-choice`.
 */
class SupabaseAuthRepository(
    private val log: Logger,
    private val gateway: AuthGateway,
    private val sessionStore: SecureSessionStore,
    /**
     * Where the local owner's id and the offline-mode marker live.
     *
     * Plain DataStore, not [sessionStore]: neither value is a secret, and on the
     * desktop every read through the keychain is a `secret-tool` subprocess.
     */
    private val localOwnerStore: DataStoreSessionStore,
    private val scope: com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope,
) : AuthRepository {

    private val _currentSession = MutableStateFlow<Session>(Session.Loading)
    override val currentSession: StateFlow<Session> = _currentSession.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * `replay = 1`, and it is load-bearing rather than generous.
     *
     * The repository is a Koin single resolved during startup, so the restore runs before
     * anything is on screen. `AuthViewModel` is constructed later, when the login screen
     * composes — and it is the only thing that can show the message. With `replay = 0` the
     * emission finds no subscriber and is dropped, so the message the whole mechanism
     * exists to deliver would never arrive, on any device that failed to restore.
     *
     * ## Why a channel and not a replaying flow
     *
     * Both obvious choices are wrong, in opposite directions, and each was wrong in a way
     * that only showed up in a test:
     *
     * - `MutableSharedFlow(replay = 0)` drops the emission, because the repository is a
     *   startup singleton and restores long before any view model exists. The message
     *   never arrives.
     * - `MutableSharedFlow(replay = 1)` delivers it — and then delivers it *again*, to
     *   every view model built later in the same launch. Sign out after "Continue Offline"
     *   and the login screen greets you with "could not read your saved session" about a
     *   session it read perfectly a minute ago.
     *
     * A buffered channel is the one structure with the right ownership: an element sent
     * before anyone is listening stays buffered until something takes it, and `receive`
     * takes it **once**. Exactly one screen learns about one failure.
     *
     * `Channel.BUFFERED` also keeps `trySend` from suspending, so the recovery path in
     * [restoreSession] cannot block on a subscriber — which would be a second way for a
     * broken store to stall the launch it is already recovering from.
     *
     * ## Why the channel is closed when the restore is over
     *
     * The restore runs once per repository instance, so the report can be published at
     * most once and only by [restoreSession]. Closing the channel when that restore is
     * over says exactly that, and it is what makes the absence of a failure an answer
     * rather than an absence of information: a reader waiting for a report learns there
     * was none instead of waiting forever for an event that cannot arrive.
     */
    private val _restoreFailures = Channel<Unit>(Channel.BUFFERED)
    override val restoreFailures: Flow<Unit> = _restoreFailures.receiveAsFlow()

    /**
     * The scope the restore runs in.
     *
     * A scope of its own, and deliberately not the scope [restoreSession] is called
     * from — that separation is the whole bound (see [boundedRead]).
     *
     * It borrows the repository's scope for everything except the job: the dispatcher
     * and the failure handler, so a failure in the restore is reported the way every
     * other background failure in this app is reported. The job is its own, and that
     * is what makes this a scope of its own:
     *
     * - The restore is not a child of the coroutine that waits for it, so the caller
     *   can walk away from a restore that ignores cancellation.
     * - The restore is not a child of the repository's scope either — that scope *is*
     *   the caller's — so a restore the caller has given up on does not keep the
     *   caller's scope from completing. A caller that has been released must not still
     *   be carrying the work it released.
     *
     * The scope's lifetime is the repository's: it is a property of this class, it is
     * never cancelled explicitly, and the repository is a process-lifetime singleton —
     * the same bargain every other background scope in this app makes.
     */
    private val restoreScope: CoroutineScope = CoroutineScope(
        scope.coroutineContext.minusKey(Job) + SupervisorJob(),
    )

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
     *
     * With no token there are two possible answers, and the marker decides which.
     * No marker is a fresh install or a signed-out account, and both want the login
     * screen. A marker means the user asked to work without an account, and
     * asking again on every launch would be the auth screen reappearing over an
     * app they had already been let into — the symptom this whole path exists to
     * remove.
     *
     * ## The deadline
     *
     * Before PR #720 the "no token" path was synchronous and instant. Now it
     * reads from [localOwnerStore] via DataStore, which can hang on a corrupted
     * preferences file or a locked store. A deadline prevents an unreachable
     * storage from leaving the app behind a permanent [Session.Loading] spinner.
     *
     * ## The deadline is a bound the caller can leave
     *
     * The deadline used to be a `withTimeoutOrNull` around the restore, in the
     * caller's own coroutine. That is a *cooperative* deadline: it ends the wait
     * only if the restore honours cancellation, and a restore blocked inside a
     * driver call — a keychain read that never answers, a preferences file locked
     * by another process — ignores cancellation completely. The bound was
     * decoration, and the caller waited for the restore anyway.
     *
     * So the restore runs in a scope of its own ([restoreScope]) and the caller
     * waits on a promise of its answer (see [boundedRead]). A promise can be given
     * up on whether or not the work behind it finishes: at the deadline the caller
     * is released, and the restore keeps running where it is.
     *
     * ## Why a deadline alone is not enough
     *
     * A hung store and a *throwing* store produce the same symptom from outside,
     * and only one of them is a deadline. `EncryptedSharedPreferences` throws
     * rather than blocks when its ciphertext does not match its key — the state
     * an uninstall/reinstall leaves behind, because Android's Auto Backup
     * restores `shared_prefs/` but cannot restore the Android Keystore key that
     * decrypts it. AndroidX documents the exclusion as mandatory, and the
     * exception surfaces as `GeneralSecurityException` / `KeyStoreException`.
     *
     * A read that throws is a failed read, not a slow one, and the bound is not a
     * licence to swallow it: it is caught below and answered the way a deadline
     * is answered, because that is what the two mean to the user.
     *
     * ## What the caller leaves behind
     *
     * A restore that is still running when the deadline passed was not stopped,
     * and it may yet fail. Nobody is left to handle that failure, so it is
     * reported ([reportAbandonedRestoreFailure]) rather than dropped. It may also
     * yet succeed, and then the session it restored is published: the deadline
     * ended the waiting, not the restore.
     */
    private suspend fun restoreSession() {
        // 10 seconds is long enough for any legitimate storage operation (first
        // EncryptedSharedPreferences init, DataStore read, keychain access) and
        // short enough to not feel like a freeze when storage is truly unreachable.
        val outcome = runCatchingCancellable {
            restoreScope.boundedRead(
                timeoutMs = RESTORE_SESSION_TIMEOUT,
                onAbandonedFailure = ::reportAbandonedRestoreFailure,
            ) { doRestoreSession() }
        }.getOrElse { failure ->
            log.e(failure) { "Session restore failed outright; falling back to the login screen" }
            null
        }

        // A timeout arrives as `TimedOut`, a throw as `null`, and a completed
        // restore as `Completed` — and the first two mean the same thing to the
        // user: this install has no session it can prove, so the login screen is
        // the honest answer and "Continue Offline" is one tap away.
        if (outcome !is BoundedReadResult.Completed) {
            _currentSession.value = Session.SignedOut
            // The channel is still open here — it is closed by
            // [closeRestoreFailureWindow] right after — so the report is buffered for a
            // reader that arrives later, which is the whole point of a buffered channel.
            val published = _restoreFailures.trySend(Unit)
            if (published.isFailure) {
                // The window is closed, so there is nowhere left to publish to. The
                // restore runs once per repository instance, so this says the
                // assumption behind [closeRestoreFailureWindow] no longer holds — and
                // it says it out loud rather than dropping the report.
                log.w { "A restore failure arrived after the failure window was closed" }
            }
        }
        closeRestoreFailureWindow()
    }

    /**
     * Closes the window in which a restore failure can be reported.
     *
     * The restore runs once per repository instance, so the report can be published at
     * most once and only by [restoreSession]. Closing the channel here says exactly
     * that, and it is what turns "there was no failure" from something a reader has to
     * guess into something it is told.
     */
    private fun closeRestoreFailureWindow() {
        _restoreFailures.close()
    }

    /**
     * A restore that failed after the repository had given up on it.
     *
     * The repository stopped waiting at the deadline, but the restore kept running:
     * nobody is left to handle a failure it produces now, so it is reported here
     * rather than dropped on the floor. The same shape the remote-config port uses
     * for a read it abandoned.
     */
    private fun reportAbandonedRestoreFailure(cause: Throwable) {
        log.w(cause) { "Session restore failed after the repository had given up on it" }
    }

    /**
     * The actual session restore logic, extracted so the deadline wraps
     * the whole operation without conflating the timeout concern with the logic.
     */
    private suspend fun doRestoreSession() {
        val refreshToken = sessionStore.currentRefreshToken()
        val userId = sessionStore.currentUserId()
        val email = sessionStore.currentEmail()
        if (refreshToken == null || userId == null) {
            restoreLocalSessionOrSignOut()
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

    /**
     * Resumes the local session if this install is in offline mode, else signs out.
     *
     * Split from [restoreSession] so the "no token" branch reads as one decision
     * rather than a second thing to get wrong when the restore path changes.
     */
    private suspend fun restoreLocalSessionOrSignOut() {
        if (!localOwnerStore.isLocalSessionActive()) {
            _currentSession.value = Session.SignedOut
            return
        }
        runCatchingResult {
            UserId.fromString(localOwnerStore.getOrInitLocalOwnerId())
        }.fold(
            // A read that fails leaves the user signed out rather than half-signed-in:
            // the sign-in screen can always start the local session again, and the id
            // it generates is the same one the rows are already filed under.
            onSuccess = { _currentSession.value = Session.Anonymous(it) },
            onFailure = {
                log.w(it) { "Could not read the local owner id; falling back to the login screen" }
                _currentSession.value = Session.SignedOut
            },
        )
    }

    override suspend fun signUp(email: String, password: String): Result<Unit> = attempt("sign up", email) {
        val address = AuthDomain.normalizeEmail(email)
        AuthDomain.validateEmail(address)
        AuthDomain.validatePassword(password)
        gateway.signUp(address, password)
    }

    override suspend fun signIn(email: String, password: String): Result<Unit> = attempt("sign in", email) {
        val address = AuthDomain.normalizeEmail(email)
        AuthDomain.validateEmail(address)
        AuthDomain.validatePassword(password)
        gateway.signIn(address, password)
    }

    /**
     * Publishes a local, offline session and remembers that this install chose it.
     *
     * ## Why there is no gateway call
     *
     * This used to be `attempt("anonymous sign in") { gateway.signInAnonymously() }`,
     * which created a real provider-side anonymous user. That is a different operation
     * from the one the button promises. It needed a configured Supabase project, a
     * working network and anonymous sign-ins enabled in the project's settings, so
     * pressing a button labelled "Continue Offline" on a plane produced an error and
     * left the user on the auth screen — the exact failure the offline path exists to
     * prevent. ADR `2026-10-05-anonymous-is-local-only-and-signing-in-offers-a-choice`
     * had already ruled the provider call dead; this is the code catching up.
     *
     * ## Why a storage failure is a failure and not a success
     *
     * A session that is published but not remembered is worse than no session at all:
     * the user is inside the app, believes their work is safe, and finds at the next
     * launch that they were signed out — with rows under an id the next local session
     * will not generate. So the marker is written before the session is published, and
     * a store that refuses turns the whole attempt into the reported failure it is.
     */
    override suspend fun startLocalSession(): Result<Unit> {
        _isLoading.value = true
        return try {
            runCatchingResult {
                val owner = UserId.fromString(localOwnerStore.getOrInitLocalOwnerId())
                localOwnerStore.markLocalSessionActive()
                _currentSession.value = Session.Anonymous(owner)
            }.map { }.onFailure { e ->
                log.e(e) { "Auth could not start a local session" }
            }
        } finally {
            _isLoading.value = false
        }
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
     *
     * ## Why the write to the store is inside the captured block
     *
     * Storing the session was the last statement of a success handler, and a handler
     * that throws is not caught by the result it is attached to — so a keychain that
     * failed while writing turned a failed attempt into an exception that left the
     * repository, and from there an uncaught failure in the view model's scope. The
     * user saw nothing at all, and the account existed at the provider holding a live
     * session the device had never learned about. Doing the write inside the block
     * makes it a failure of the attempt, which is what it is: the attempt got as far
     * as the provider and then could not be completed here.
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
            runCatchingResult {
                val remote = block().getOrThrow()
                if (remote.email.isNullOrBlank() && !remote.isAnonymous) {
                    _currentSession.value = Session.SignedOut
                } else {
                    apply(remote, remote.email.orEmpty())
                }
            }
                .map { }
                .onFailure { e ->
                    if (e.toAppError().code == SecureSessionStore.STORAGE_FAILED) endOrphanedSession(what)
                    log.e(e) { "Auth $what failed [email=${email?.let(Redaction::redactEmail) ?: "none"}]" }
                }
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Ends a session the provider issued and the device could not store.
     *
     * Without this the provider is left holding a live session for an account the
     * user believes failed, and nothing will ever revoke it: the refresh token is
     * gone, so no later request carries it. The call is best-effort and its own
     * failure is not reported over the storage failure that is already the answer to
     * "why am I not signed in" — the message says the attempt reached the provider, so
     * a user who cares knows to confirm the account.
     */
    private suspend fun endOrphanedSession(what: String) {
        gateway.signOut().onFailure {
            log.w(it) { "Auth $what left a session at the provider that could not be ended" }
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

    /**
     * Ends the session and forgets that this install was running offline.
     *
     * The local owner's **id** survives. Signing out is about a session, not about
     * data, and REQ-UA-006 keeps local rows on a sign-out; erasing the id would leave
     * every task created offline unreachable without any error to explain it. Clearing
     * the marker is what makes the next launch show the login screen, where a
     * different account can be chosen or the same offline session resumed.
     */
    override suspend fun signOut(): Result<Unit> {
        _isLoading.value = true
        return try {
            val result = runCatchingResult {
                sessionStore.clear()
                localOwnerStore.clearLocalSession()
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

    override suspend fun updatePassword(newPassword: String): Result<Unit> {
        _isLoading.value = true
        return try {
            runCatchingResult {
                AuthDomain.validatePassword(newPassword)
                gateway.updatePassword(newPassword).getOrThrow()
            }.onFailure { e ->
                log.e(e) { "Auth password update failed" }
            }
        } finally {
            _isLoading.value = false
        }
    }

    override suspend fun sendMagicLink(email: String): Result<Unit> {
        _isLoading.value = true
        return try {
            val address = AuthDomain.normalizeEmail(email)
            runCatchingResult {
                AuthDomain.validateEmail(address)
                gateway.sendMagicLink(address).getOrThrow()
            }.onFailure { e ->
                log.e(e) { "Auth magic link send failed [email=${Redaction.redactEmail(address)}]" }
            }
        } finally {
            _isLoading.value = false
        }
    }

    override suspend fun verifyMagicLink(email: String, token: String): Result<Unit> {
        _isLoading.value = true
        return try {
            runCatchingResult {
                val session = gateway.verifyMagicLink(email, token).getOrThrow()
                apply(session, email)
            }.onFailure { e ->
                log.e(e) { "Auth magic link verification failed [email=${Redaction.redactEmail(email)}]" }
            }
        } finally {
            _isLoading.value = false
        }
    }

    private companion object {
        /**
         * Timeout for [restoreSession]. Chosen to be long enough for any legitimate
         * storage operation (first EncryptedSharedPreferences init, DataStore read) yet
         * short enough to avoid a visible freeze when storage is unreachable.
         */
        const val RESTORE_SESSION_TIMEOUT = 10_000L
    }
}

/**
 * The default for [AuthRepository.restoreFailures]: an empty flow, which completes at once.
 *
 * "There is no restore, so there is no restore failure" is a *finished* answer, not a
 * pending one. A flow that never emits would leave every collector of it
 * suspended for the life of the process — harmless behind a background scope in production,
 * and `UncompletedCoroutinesError` in every test that hands the view model a test scope,
 * because `runTest` waits for foreground coroutines and reports the wait regardless of
 * what the assertions underneath said.
 */
private val NoRestoreFailures: Flow<Unit> = emptyFlow()
