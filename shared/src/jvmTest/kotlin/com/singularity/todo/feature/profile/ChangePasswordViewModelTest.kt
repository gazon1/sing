@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.profile

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.feature.profile.presentation.ChangePasswordIntent
import com.singularity.todo.feature.profile.presentation.ChangePasswordUiState
import com.singularity.todo.feature.profile.presentation.ChangePasswordViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId

/**
 * Tests for [ChangePasswordViewModel].
 */
@Tag("fast")
class ChangePasswordViewModelTest {

    private fun TestScope.viewmodel(
        updatePassword: suspend (String) -> Result<Unit> = { Result.success(Unit) },
    ): ChangePasswordViewModel {
        val repository = object : AuthRepository {
            override val currentSession: StateFlow<Session> = MutableStateFlow(
                Session.SignedIn(
                    UserId("u"),
                    "t@x.com",
                    "a",
                    "r",
                ),
            )
            override val isLoading: StateFlow<Boolean> = MutableStateFlow(false)
            override suspend fun signUp(email: String, password: String): Result<Unit> = Result.success(Unit)
            override suspend fun signIn(email: String, password: String): Result<Unit> = Result.success(Unit)
            override suspend fun signInAnonymously(): Result<Unit> = Result.success(Unit)
            override suspend fun signOut(): Result<Unit> = Result.success(Unit)
            override suspend fun migrateAnonymousTo(
                email: String,
                password: String,
            ): Result<Unit> = Result.success(Unit)
            override suspend fun updatePassword(newPassword: String): Result<Unit> = updatePassword(newPassword)
            override suspend fun sendMagicLink(email: String): Result<Unit> = Result.success(Unit)
            override suspend fun verifyMagicLink(email: String, token: String): Result<Unit> = Result.success(Unit)
        }
        return ChangePasswordViewModel(
            authRepository = repository,
            scope = AutoCloseableCoroutineScope(coroutineContext),
        )
    }

    @Test
    fun `submitting a valid password transitions from Idle through Loading to Success`() = runTest {
        val vm = viewmodel()

        vm.onIntent(ChangePasswordIntent.Submit("ValidPassword123"))
        advanceUntilIdle()

        assertIs<ChangePasswordUiState.Success>(vm.state.value)
    }

    @Test
    fun `a failed submission leaves the state as Idle`() = runTest {
        val vm = viewmodel { Result.failure(AppError.Validation("Password change failed")) }

        vm.onIntent(ChangePasswordIntent.Submit("ValidPassword123"))
        advanceUntilIdle()

        assertIs<ChangePasswordUiState.Idle>(vm.state.value)
    }

    @Test
    fun `Reset returns state to Idle regardless of current state`() = runTest {
        val vm = viewmodel()

        // Transition to Success first.
        vm.onIntent(ChangePasswordIntent.Submit("ValidPassword123"))
        advanceUntilIdle()
        assertIs<ChangePasswordUiState.Success>(vm.state.value)

        // Reset clears to Idle.
        vm.onIntent(ChangePasswordIntent.Reset)
        advanceUntilIdle()
        assertIs<ChangePasswordUiState.Idle>(vm.state.value)
    }
}
