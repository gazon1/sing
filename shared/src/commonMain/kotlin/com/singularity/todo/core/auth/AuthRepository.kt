package com.singularity.todo.core.auth

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.log.Redaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Repository interface for authentication.
 */
interface AuthRepository {
    val currentSession: StateFlow<Session>
    val isLoading: StateFlow<Boolean>
    suspend fun signUp(email: String, password: String): Result<Unit>
    suspend fun signIn(email: String, password: String): Result<Unit>
    suspend fun signInAnonymously(): Result<Unit>
    suspend fun signOut(): Result<Unit>
    suspend fun migrateAnonymousTo(newUserId: UserId): Result<Unit>
}

/**
 * Stub implementation of AuthRepository for compilation.
 * TODO: Replace with actual Supabase implementation once SDK is properly integrated.
 */
class SupabaseAuthRepository(
    private val log: Logger,
    private val sessionStore: SessionStore,
    private val scope: AutoCloseableCoroutineScope,
) : AuthRepository {

    private val _currentSession = MutableStateFlow<Session>(Session.Anonymous(UserId.anonymous))
    override val currentSession: StateFlow<Session> = _currentSession.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        scope.launch {
            val deviceId = sessionStore.getOrInitDeviceId()
            _currentSession.value = Session.Anonymous(UserId.fromString(deviceId))
        }
    }

    override suspend fun signUp(email: String, password: String): Result<Unit> {
        _isLoading.value = true
        val result = runCatchingResult {
            AuthDomain.validateEmail(email)
            AuthDomain.validatePassword(password)
            // TODO: Implement with Supabase SDK
            _isLoading.value = false
        }
        result.onFailure { e -> log.e(e) { "signUp failed [email=${Redaction.redactEmail(email)}]" } }
        return result
    }

    override suspend fun signIn(email: String, password: String): Result<Unit> {
        _isLoading.value = true
        val result = runCatchingResult {
            AuthDomain.validateEmail(email)
            AuthDomain.validatePassword(password)
            // TODO: Implement with Supabase SDK
            _isLoading.value = false
        }
        result.onFailure { e -> log.e(e) { "signIn failed [email=${Redaction.redactEmail(email)}]" } }
        if (result.isFailure) _isLoading.value = false
        return result
    }

    override suspend fun signInAnonymously(): Result<Unit> = runCatchingResult {
        val deviceId = sessionStore.getOrInitDeviceId()
        _currentSession.value = Session.Anonymous(UserId.fromString(deviceId))
    }

    override suspend fun signOut(): Result<Unit> {
        _isLoading.value = true
        val result = runCatchingResult {
            sessionStore.clear()
            _currentSession.value = Session.SignedOut
        }
        _isLoading.value = false
        result.onFailure { e -> log.e(e) { "signOut failed" } }
        return result
    }

    override suspend fun migrateAnonymousTo(newUserId: UserId): Result<Unit> = runCatchingResult {
        throw AppError.Unauthorized("Anonymous-to-user migration not yet implemented")
    }
}
