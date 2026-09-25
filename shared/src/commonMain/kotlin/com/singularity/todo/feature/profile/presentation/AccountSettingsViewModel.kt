package com.singularity.todo.feature.profile.presentation

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.core.ui.mvi.MviIntent
import com.singularity.todo.feature.profile.Profile
import com.singularity.todo.feature.profile.ProfileRepository
import kotlinx.coroutines.flow.Flow

/** Placeholder state — AccountSettingsViewModel only exposes activeProfile Flow. */
sealed interface AccountSettingsUiState {
    data object Idle : AccountSettingsUiState
}

sealed interface AccountSettingsIntent : MviIntent
// No user intents — purely observational

/**
 * ViewModel for [AccountSettingsScreen].
 *
 * Owns [activeProfile] — the Composable subscribes rather than calling
 * the repository directly.
 *
 * @see AccountSettingsUiState
 */
class AccountSettingsViewModel(
    profileRepository: ProfileRepository,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<AccountSettingsUiState, AccountSettingsIntent, Nothing>(
    initialState = AccountSettingsUiState.Idle,
    scope = scope,
) {

    init {
        addCloseable(scope)
    }

    val activeProfile: Flow<Profile?> = profileRepository.activeProfile()

    override fun onIntent(intent: AccountSettingsIntent) {
        // No intents yet
    }
}
