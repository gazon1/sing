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

    override suspend fun signUp(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signIn(email: String, password: String): Result<Unit> = Result.success(Unit)
    override suspend fun signInAnonymously(): Result<Unit> = Result.success(Unit)
    override suspend fun signOut(): Result<Unit> {
        _currentSession.value = Session.SignedOut
        return Result.success(Unit)
    }
    override suspend fun migrateAnonymousTo(email: String, password: String): Result<Unit> =
        Result.success(Unit)
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
