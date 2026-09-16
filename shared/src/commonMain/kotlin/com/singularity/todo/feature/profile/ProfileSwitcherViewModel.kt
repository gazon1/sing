package com.singularity.todo.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

class ProfileSwitcherViewModel(private val profileRepository: ProfileRepository) : ViewModel() {

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
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ProfileSwitcherUiState(),
    )

    fun create(name: String, emoji: String, colorIdx: Int) {
        viewModelScope.launch {
            profileRepository.create(name, emoji, colorIdx)
        }
    }

    fun rename(id: ProfileId, name: String) {
        viewModelScope.launch {
            val profile = profileRepository.getById(id) ?: return@launch
            profileRepository.update(id, name, profile.emoji, profile.colorIdx)
        }
    }

    fun delete(id: ProfileId) {
        viewModelScope.launch {
            profileRepository.delete(id)
        }
    }

    fun switchTo(id: ProfileId) {
        viewModelScope.launch {
            profileRepository.switchTo(id)
        }
    }
}
