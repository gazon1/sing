package com.singularity.todo.feature.profile

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileSwitcherUiState(
    val profiles: List<Profile> = emptyList(),
    val activeProfileId: ProfileId = ProfileId.default,
    val isLoading: Boolean = true,
)

/**
 * Profile switcher dialog ViewModel.
 *
 * Owns: all profiles and the active profile ID.
 * Triggers: profile creation, profile switch, profile deletion.
 * One-shot events: none — profile switch is applied immediately.
 *
 * @see ProfileSwitcherUiState
 */
class ProfileSwitcherViewModel(
    private val profileRepository: ProfileRepository,
    private val scope: CoroutineScope,
) : ViewModel() {

    /** Production constructor — Koin uses this. */
    constructor(profileRepository: ProfileRepository) : this(
        profileRepository = profileRepository,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    val uiState: StateFlow<ProfileSwitcherUiState> = combine(
        profileRepository.all(),
        profileRepository.activeProfileId,
    ) { profiles, activeId ->
        ProfileSwitcherUiState(
            profiles = profiles,
            activeProfileId = activeId,
            isLoading = false,
        )
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(5_000),
        ProfileSwitcherUiState(),
    )

    fun create(name: String, emoji: String, colorIdx: Int) {
        scope.launch {
            profileRepository.create(name, emoji, colorIdx)
        }
    }

    fun rename(id: ProfileId, name: String) {
        scope.launch {
            val profile = profileRepository.getById(id) ?: return@launch
            profileRepository.update(id, name, profile.emoji, profile.colorIdx)
        }
    }

    fun delete(id: ProfileId) {
        scope.launch {
            profileRepository.delete(id)
        }
    }

    fun switchTo(id: ProfileId) {
        scope.launch {
            profileRepository.switchTo(id)
        }
    }
}
