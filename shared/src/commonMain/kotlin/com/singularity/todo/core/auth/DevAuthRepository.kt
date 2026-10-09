package com.singularity.todo.core.auth

import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A no-network [AuthRepository] for local development, CI эмулятор testing,
 * and unit tests.
 *
 * **For production / dev machines with a real Supabase backend:** use
 * `SupabaseAuthRepository` instead (swap the DI binding in `CoreDiModule`).
 *
 * ## Test usage
 *
 * Create via factory methods — no Supabase classes referenced:
 * ```
 * // Anonymous session (default)
 * val repo = DevAuthRepository.anonymous()
 *
 * // Signed-in session
 * val repo = DevAuthRepository.signedIn(email = "alice@example.com")
 *
 * // Signed-in with custom userId
 * val repo = DevAuthRepository.signedIn(email = "alice@example.com", userId = UserId.generate())
 * ```
 *
 * Transition sessions in tests via [configureSession]:
 * ```
 * repo.configureSession(Session.SignedIn(UserId.fromString("user-1"), "alice@example.com", "tok", "ref"))
 * ```
 *
 * Inspect call history:
 * ```
 * repo.signInCalls        // list of (email, password) from signIn()
 * repo.signUpCalls        // list of (email, password) from signUp()
 * repo.signOutCalled      // true if signOut() was invoked
 * ```
 */
class DevAuthRepository private constructor(
    initialSession: Session,
) : AuthRepository {

    private val _currentSession = MutableStateFlow(initialSession)
    override val currentSession: StateFlow<Session> = _currentSession.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ─── Test call tracking ────────────────────────────────────────────────

    /**
     * Record of every [signIn] call: each entry is `(email, password)`.
     * Append-only; clear with [clearCallHistory].
     */
    val signInCalls = mutableListOf<Pair<String, String>>()

    /**
     * Record of every [signUp] call: each entry is `(email, password)`.
     * Append-only; clear with [clearCallHistory].
     */
    val signUpCalls = mutableListOf<Pair<String, String>>()

    /** True if [signOut] was called at least once since construction or [clearCallHistory]. */
    var signOutCalled = false
        private set

    /** True if [signInAnonymously] was called at least once since construction or [clearCallHistory]. */
    var signInAnonymouslyCalled = false
        private set

    // ─── Lifecycle ────────────────────────────────────────────────────────

    init {
        _currentSession.value = initialSession
    }

    // ─── AuthRepository implementation ────────────────────────────────────

    override suspend fun signUp(email: String, password: String): Result<Unit> {
        signUpCalls += email to password
        return Result.success(Unit)
    }

    override suspend fun signIn(email: String, password: String): Result<Unit> {
        signInCalls += email to password
        _currentSession.value = Session.SignedIn(
            userId = UserId.anonymous,
            email = email,
            accessToken = "dev-access-token",
            refreshToken = "dev-refresh-token",
        )
        return Result.success(Unit)
    }

    override suspend fun signInAnonymously(): Result<Unit> {
        signInAnonymouslyCalled = true
        _currentSession.value = Session.Anonymous(UserId.anonymous)
        return Result.success(Unit)
    }

    override suspend fun signOut(): Result<Unit> {
        signOutCalled = true
        _currentSession.value = Session.Anonymous(UserId.anonymous)
        return Result.success(Unit)
    }

    override suspend fun migrateAnonymousTo(email: String, password: String): Result<Unit> {
        signInCalls += email to password
        _currentSession.value = Session.SignedIn(
            userId = UserId.anonymous,
            email = email,
            accessToken = "dev-access-token",
            refreshToken = "dev-refresh-token",
        )
        return Result.success(Unit)
    }

    // ─── Test helpers ─────────────────────────────────────────────────────

    /**
     * Overrides the current session to any [Session] variant.
     * Use this to simulate auth state changes that would normally require
     * network calls: e.g. restoring a stored session, or switching users.
     *
     * Does NOT affect [signInCalls], [signUpCalls], etc. — those are only
     * mutated by the real repository methods.
     */
    fun configureSession(session: Session) {
        _currentSession.value = session
    }

    /** Resets all call-tracking fields to their initial state. */
    fun clearCallHistory() {
        signInCalls.clear()
        signUpCalls.clear()
        signOutCalled = false
        signInAnonymouslyCalled = false
    }

    // ─── Factory methods ──────────────────────────────────────────────────

    /** Creates a repository that starts as [Session.Anonymous]. */
    companion object {
        fun anonymous(): DevAuthRepository =
            DevAuthRepository(Session.Anonymous(UserId.anonymous))

        /**
         * Creates a repository that starts as [Session.SignedIn].
         * No Supabase or any other backend is contacted.
         */
        fun signedIn(email: String, userId: UserId = UserId.anonymous): DevAuthRepository =
            DevAuthRepository(
                Session.SignedIn(
                    userId = userId,
                    email = email,
                    accessToken = "dev-access-token",
                    refreshToken = "dev-refresh-token",
                ),
            )
    }
}
