package com.singularity.todo.feature.profile.presentation

import androidx.lifecycle.ViewModel
import com.singularity.todo.feature.profile.Profile
import com.singularity.todo.feature.profile.ProfileRepository
import kotlinx.coroutines.flow.Flow

/**
 * ViewModel for [AccountSettingsScreen].
 *
 * Owns [activeProfile] — the Composable subscribes rather than calling
 * the repository directly.
 */
class AccountSettingsViewModel(
    profileRepository: ProfileRepository,
) : ViewModel() {

    val activeProfile: Flow<Profile?> = profileRepository.activeProfile()
}
