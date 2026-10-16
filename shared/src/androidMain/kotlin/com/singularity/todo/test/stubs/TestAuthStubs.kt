package com.singularity.todo.test.stubs

import com.singularity.todo.core.auth.AuthGateway
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.RemoteSession
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An [AuthRepository] stub that is permanently signed in as a test user.
 *
 * Used for E2E smoke test builds that need to bypass the auth screen
 * without any Supabase server or network calls.
 *
 * How it works:
 *   - [SupabaseClientProvider] is NOT overridden — the real client is created from
 *     the build-time Supabase config in local.properties (dummy URL + anon key).
 *     No network calls are made at startup; the client is only used for HTTP
 *     operations that the test flows don't trigger.
 *   - [AuthGateway] is overridden with [NoOpAuthGateway] so no network auth calls fire.
 *   - [AuthRepository] is overridden with [StubAuthRepository] so the session is
 *     immediately `Session.SignedIn` and the auth screen never appears.
 */
class StubAuthRepository : AuthRepository {
    // Explicit Session type: Kotlin would otherwise infer MutableStateFlow<Session.SignedIn>
    // from the initial value, making Session.SignedOut assignment fail at compile time.
    private val _currentSession = MutableStateFlow<Session>(
        Session.SignedIn(
            userId = UserId.fromString("00000000-0000-0000-0000-000000000001"),
            email = "test@singularity.test",
            accessToken = "test-access-token",
            refreshToken = "test-refresh-token",
        ),
    )
    override val currentSession: StateFlow<Session> = _currentSession.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

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
     * When `true`, [signUp] returns a failure result, simulating a network error
     * or email already registered server response.
     *
     * Driven by `debug.stub_auth.signup_failure` system property (lazy read).
     */
    var signUpFailure = false

    /**
     * When `true`, [migrateAnonymousTo] returns a failure result, simulating
     * a network error or ownership-conflict server response.
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
        return Result.success(Unit)
    }

    override suspend fun signIn(email: String, password: String): Result<Unit> {
        if (signInFailure || isFailurePropSet("debug.stub_auth.signin_failure")) {
            signInFailure = false
            return networkFailure()
        }
        return Result.success(Unit)
    }
    override suspend fun signInAnonymously(): Result<Unit> {
        _currentSession.value = Session.Anonymous(
            userId = UserId.fromString("00000000-0000-0000-0000-000000000001"),
        )
        return Result.success(Unit)
    }

    override suspend fun signOut(): Result<Unit> {
        _currentSession.value = Session.SignedOut
        return Result.success(Unit)
    }

    override suspend fun migrateAnonymousTo(email: String, password: String): Result<Unit> {
        if (attachFailure || isFailurePropSet("debug.stub_auth.attach_failure")) {
            attachFailure = false
            return networkFailure()
        }
        // StubAuthRepository keeps the same userId across anonymous→signed-in,
        // mirroring how the real Supabase provider attaches credentials to the
        // existing identity without changing the owner.
        _currentSession.value = Session.SignedIn(
            userId = UserId.fromString("00000000-0000-0000-0000-000000000001"),
            email = email,
            accessToken = "test-access-token",
            refreshToken = "test-refresh-token",
        )
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

    override suspend fun signInAnonymously(): Result<RemoteSession> =
        Result.success(dummyAnonymousSession())

    override suspend fun attachEmail(email: String, password: String): Result<RemoteSession> =
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
