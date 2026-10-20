package com.singularity.todo.test.stubs

import android.content.Context
import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.AuthGateway
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.RemoteSession
import com.singularity.todo.core.auth.SecureSessionStore
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ids.UserId
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The debug-only install path for a session.
 *
 * ## What it writes
 *
 * A [Session.SignedIn] through [SecureSessionStore] — the same call a real login
 * makes — so the session lands in the app's encrypted store, under the same
 * keys and in the same shape, as a session the server issued. A flow that
 * installs a session this way exercises the store the app really uses, not a
 * parallel one written for tests.
 *
 * ## Why it exists
 *
 * The E2E build replaces the auth repository with a stub. While the stub kept
 * its session in memory only, nothing could install a *persisted* session, and
 * a flow that damaged the store had nothing to damage: the store was never
 * written, so the flow's claim — that the app recovers from a store it cannot
 * read — was a claim about a file the app did not use.
 *
 * ## Where it is reachable from
 *
 * This object lives in the shared library's Android source set, so it is
 * compiled into every variant of the app. The *paths* to it are not: the only
 * caller is the debug seed activity, which lives in the app's `src/debug`
 * source set, and no release APK contains that source set. A release build
 * therefore has no way to install a session — not a hidden one, none.
 */
object TestAuthStubs {

    /** The account the E2E build signs in as. */
    const val DEFAULT_EMAIL = "test@singularity.test"

    /** The owner id of [DEFAULT_EMAIL]. A fixed id, so a flow can name it. */
    const val DEFAULT_USER_ID = "00000000-0000-0000-0000-000000000001"

    /** A token the app will never send anywhere: the E2E build has no server. */
    const val DEFAULT_ACCESS_TOKEN = "test-access-token"

    /** The refresh token that goes with [DEFAULT_ACCESS_TOKEN]. */
    const val DEFAULT_REFRESH_TOKEN = "test-refresh-token"

    /**
     * Where the app's encrypted store lives, relative to the app's data
     * directory.
     *
     * The name is the one `AndroidSecureStorage` opens. The Maestro scripts
     * that damage the store name the same file.
     */
    const val SECURE_STORE_PATH = "shared_prefs/singularity_secure_prefs.xml"

    /**
     * The file that records that this install has a session that was not
     * signed out of.
     *
     * ## Why it is not in the store
     *
     * The store cannot answer the question the restore has to ask: "was there
     * a session here, and is it gone now?" A store that was damaged is erased
     * and recreated empty by the app's recovery, so an empty store looks the
     * same as an install that never had one. The marker lives outside the
     * store for the same reason the damage does: a flow that destroys the
     * store must not destroy the evidence that a session was in it.
     *
     * It is a plain file, so a flow can see it, and it holds no secret: it
     * says that a session was installed, not which one.
     */
    const val INSTALLED_MARKER = "e2e-session-installed"

    /** The session the E2E build installs when it has none. */
    fun defaultSession(email: String = DEFAULT_EMAIL): Session.SignedIn = Session.SignedIn(
        userId = UserId.fromString(DEFAULT_USER_ID),
        email = email,
        accessToken = DEFAULT_ACCESS_TOKEN,
        refreshToken = DEFAULT_REFRESH_TOKEN,
    )

    /** The file the app's encrypted store is written to. */
    fun secureStoreFile(context: Context): File =
        File(context.applicationInfo.dataDir, SECURE_STORE_PATH)

    /** The file that records that this install has a session. */
    fun installedMarkerFile(context: Context): File = File(context.filesDir, INSTALLED_MARKER)

    /** Whether this install has a session that was not signed out of. */
    fun hasInstalledSession(context: Context): Boolean = installedMarkerFile(context).exists()

    /**
     * Forgets that this install had a session.
     *
     * Called when the user signs out — a sign-out is a deliberate "no session
     * here", and the E2E build starts signed in again — and when the restore
     * has reported a lost session, so that the loss is reported once and the
     * next launch is a fresh start rather than a second report of the same
     * loss.
     */
    fun forgetInstalledSession(context: Context) {
        runCatching { installedMarkerFile(context).delete() }
    }

    /**
     * Writes a session into the app's encrypted store.
     *
     * The write goes through [SecureSessionStore], so it lands in the same keys,
     * in the same shape, and with the same undo-on-partial-write behaviour as a
     * session the server issued.
     */
    suspend fun installSignedInSession(
        sessionStore: SecureSessionStore,
        context: Context,
        email: String = DEFAULT_EMAIL,
    ): Result<Unit> = runCatchingCancellable {
        sessionStore.save(defaultSession(email))
        installedMarkerFile(context).writeText(email)
    }

    /**
     * The session the store holds, or null when it holds none.
     *
     * All four values are read or none is: a session whose tokens and whose
     * owner disagree is worse than no session, and the store can be read
     * halfway through a write.
     */
    suspend fun storedSession(sessionStore: SecureSessionStore): Session.SignedIn? {
        val accessToken = sessionStore.currentAccessToken()
        val refreshToken = sessionStore.currentRefreshToken()
        val email = sessionStore.currentEmail()
        val userId = sessionStore.currentUserId()
        if (accessToken == null || refreshToken == null) {
            return null
        }
        if (email == null || userId == null) {
            return null
        }
        return Session.SignedIn(
            userId = UserId.fromString(userId),
            email = email,
            accessToken = accessToken,
            refreshToken = refreshToken,
        )
    }
}

/**
 * An [AuthRepository] stub for E2E builds: no server, no network, and a session
 * that lives in the app's real encrypted store.
 *
 * ## What it does
 *
 *   - [AuthGateway] is replaced by [NoOpAuthGateway] in the Koin graph, so no
 *     network auth call ever fires.
 *   - The session is restored from the [SecureSessionStore] the real login
 *     writes to, so a flow that signs the user out, damages the store or
 *     restarts the app exercises the app's own session path.
 *   - An install with no session to restore gets one: the E2E build starts
 *     signed in, which is what lets a flow reach the agenda without a server.
 *
 * ## Why the session is persisted at all
 *
 * While the stub kept its session in memory only, the store was never written
 * and never read. A flow that damaged the store then had nothing to damage,
 * and its claim — that the app recovers from a store it cannot read — was a
 * claim about a file the app did not use. The store is the app's own, so the
 * flow now damages the real thing.
 *
 * ## Why the restore can end signed out
 *
 * A store that cannot be read is erased by the app's recovery, and the session
 * goes with it. The stub can see that happen: the store's file is there before
 * the read and gone after it. That is the difference between an install that
 * never had a session and an install whose session was lost, and it is what
 * makes "the app signs the user out when the session is invalidated" a thing
 * this build can show.
 */
class StubAuthRepository(
    private val sessionStore: SecureSessionStore,
    private val context: Context,
    private val scope: AutoCloseableCoroutineScope,
) : AuthRepository {

    private val log = Logger.withTag("StubAuthRepository")

    // Explicit Session type: Kotlin would otherwise infer MutableStateFlow<Session.SignedIn>
    // from the initial value, making Session.SignedOut assignment fail at compile time.
    private val _currentSession = MutableStateFlow<Session>(Session.Loading)
    override val currentSession: StateFlow<Session> = _currentSession.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * The restore, started as soon as the stub is built.
     *
     * A property initializer rather than a `launch` in an `init` block: both
     * start during construction, but the initializer runs after the fields
     * above are assigned, so the restore can never observe a half-built stub.
     * The job is held so the restore can be reasoned about from the field.
     */
    private val restoreJob = scope.launch { restoreSession() }

    /**
     * When `true`, the next [signUp] call returns success but leaves the session as
     * [Session.SignedOut] — simulating a Supabase sign-up with email confirmation required.
     *
     * This drives the `AUTH-SIGNUP-02` axis in Maestro E2E flows.
     *
     * Set via `adb shell setprop debug.stub_auth.unconfirmed_signup true` before
     * the app starts, or set this property directly when using the stub in tests.
     * The system property is checked on every call (no caching) so that `setprop`
     * in a Maestro `onFlowStart` hook takes effect without restarting the app.
     */
    var signUpReturnsUnconfirmed = false

    /**
     * When `true`, [signIn] returns a failure result, simulating a network error
     * or invalid credentials server response.
     *
     * Driven by `debug.stub_auth.signin_failure` system property (lazy read).
     */
    var signInFailure = false

    /**
     * When `true`, [signUp] returns a failure result, simulating a network error or
     * email already registered server response.
     *
     * Driven by `debug.stub_auth.signup_failure` system property (lazy read).
     */
    var signUpFailure = false

    /**
     * When `true`, [signIn] returns a failure result, simulating the network error or
     * a rejected password on the attach screen.
     *
     * Driven by `debug.stub_auth.attach_failure` system property (lazy read).
     */
    var attachFailure = false

    private fun isUnconfirmedPropSet(): Boolean =
        try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", "debug.stub_auth.unconfirmed_signup"))
            process.inputStream.bufferedReader().readText().trim() == "true"
        } catch (_: Exception) {
            false
        }

    private fun isFailurePropSet(prop: String): Boolean =
        try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", prop))
            process.inputStream.bufferedReader().readText().trim() == "true"
        } catch (_: Exception) {
            false
        }

    private fun networkFailure(): Result<Nothing> = Result.failure(
        Exception("StubAuthRepository: simulated network error"),
    )

    /**
     * Restores the session the way the app does — from the store — and decides
     * what an install without one means.
     *
     * Three outcomes, and the difference between the last two is the whole
     * point of the stub reading the store at all:
     *
     *  - a session in the store: the app is signed in as that session;
     *  - no session, but this install had one that was not signed out of: the
     *    session was lost — the store was damaged, or invalidated — and the
     *    app is signed out, which is the app's own answer to a lost session;
     *  - no session and no history of one: this install has nothing to
     *    restore, so the E2E build installs a session and starts signed in.
     */
    private suspend fun restoreSession() {
        val stored = TestAuthStubs.storedSession(sessionStore)

        if (stored != null) {
            _currentSession.value = stored
            return
        }

        if (TestAuthStubs.hasInstalledSession(context)) {
            log.w { "The session is gone from the store; the app is signed out" }
            // The loss is reported once: the next launch is a fresh start, not
            // a second report of the same loss.
            TestAuthStubs.forgetInstalledSession(context)
            _currentSession.value = Session.SignedOut
            return
        }

        val installed = TestAuthStubs.installSignedInSession(sessionStore, context)
        if (installed.isFailure) {
            log.e(installed.exceptionOrNull()) { "Could not install the test session" }
        }
        _currentSession.value = TestAuthStubs.defaultSession()
    }

    override suspend fun signUp(email: String, password: String): Result<Unit> {
        if (signUpFailure || isFailurePropSet("debug.stub_auth.signup_failure")) {
            signUpFailure = false
            return networkFailure()
        }
        if (signUpReturnsUnconfirmed || isUnconfirmedPropSet()) {
            signUpReturnsUnconfirmed = false
            _currentSession.value = Session.SignedOut
            return Result.success(Unit)
        }
        return establishSession(email)
    }

    override suspend fun signIn(email: String, password: String): Result<Unit> {
        if (signInFailure || attachFailure || isFailurePropSet("debug.stub_auth.signin_failure")) {
            signInFailure = false
            return networkFailure()
        }
        return establishSession(email)
    }

    /**
     * The session a successful sign-in or sign-up leaves behind.
     *
     * The real repository persists the session it is given, and so does this
     * one: a flow that signs in through the form leaves a session in the store,
     * and the app comes up signed in after a restart instead of pretending it
     * never signed in.
     */
    private suspend fun establishSession(email: String): Result<Unit> =
        TestAuthStubs.installSignedInSession(sessionStore, context, email).fold(
            onSuccess = {
                _currentSession.value = TestAuthStubs.defaultSession(email)
                Result.success(Unit)
            },
            onFailure = { failure ->
                log.e(failure) { "Could not store the session the sign-in established" }
                Result.failure(failure)
            },
        )

    override suspend fun startLocalSession(): Result<Unit> {
        _currentSession.value = Session.Anonymous(
            userId = UserId.fromString(TestAuthStubs.DEFAULT_USER_ID),
        )
        return Result.success(Unit)
    }

    override suspend fun signOut(): Result<Unit> {
        // The real repository clears the store when it signs out, and so does
        // this one: a signed-out app has no session left to restore.
        runCatchingCancellable { sessionStore.clear() }
            .onFailure { log.e(it) { "Could not clear the session store" } }
        // The history of a session goes with it. A sign-out is a deliberate
        // "no session here", and the E2E build starts signed in again — a
        // launch after a sign-out must not look like a session that was lost.
        TestAuthStubs.forgetInstalledSession(context)
        _currentSession.value = Session.SignedOut
        return Result.success(Unit)
    }

    override suspend fun updatePassword(newPassword: String): Result<Unit> = Result.success(Unit)

    override suspend fun sendMagicLink(email: String): Result<Unit> = Result.success(Unit)

    override suspend fun verifyMagicLink(email: String, token: String): Result<Unit> = Result.success(Unit)
}

/**
 * An [AuthGateway] stub that never makes network calls.
 * All operations return success immediately with a dummy session.
 */
class NoOpAuthGateway : AuthGateway {
    override suspend fun signUp(email: String, password: String): Result<RemoteSession> =
        Result.success(dummySignedInSession())

    override suspend fun signIn(email: String, password: String): Result<RemoteSession> =
        Result.success(dummySignedInSession())

    override suspend fun signOut(): Result<Unit> = Result.success(Unit)

    override suspend fun refresh(accessToken: String, refreshToken: String): Result<RemoteSession?> =
        Result.success(dummySignedInSession())

    override suspend fun updatePassword(newPassword: String): Result<Unit> = Result.success(Unit)

    override suspend fun sendMagicLink(email: String): Result<Unit> = Result.success(Unit)

    override suspend fun verifyMagicLink(email: String, token: String): Result<RemoteSession> =
        Result.success(dummySignedInSession())
}

private fun dummySignedInSession() = RemoteSession(
    userId = "00000000-0000-0000-0000-000000000001",
    email = "test@singularity.test",
    accessToken = "test-access-token",
    refreshToken = "test-refresh-token",
    isAnonymous = false,
)

private fun dummyAnonymousSession() = RemoteSession(
    userId = "00000000-0000-0000-0000-000000000001",
    email = null,
    accessToken = "test-access-token",
    refreshToken = "test-refresh-token",
    isAnonymous = true,
)
