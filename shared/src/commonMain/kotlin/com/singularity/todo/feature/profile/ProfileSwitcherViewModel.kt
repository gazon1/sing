package com.singularity.todo.feature.profile

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.coroutines.fireAndForget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.cancel

data class ProfileSwitcherUiState(
    val profiles: List<Profile> = emptyList(),
    val activeProfileId: ProfileId = ProfileId.default,
    val isLoading: Boolean = true,
    /** Non-null when the last mutation failed. */
    val errorMessage: String? = null,
)

/** Clears any errorMessage in the UI state. */
data object DismissError

/**
 * Profile switcher dialog ViewModel.
 *
 * Owns: all profiles and the active profile ID.
 * Triggers: profile creation, profile switch, profile deletion.
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

    private val _errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ProfileSwitcherUiState> = combine(
        profileRepository.all(),
        profileRepository.activeProfileId,
        _errorMessage,
    ) { profiles, activeId, errorMsg ->
        ProfileSwitcherUiState(
            profiles = profiles,
            activeProfileId = activeId,
            isLoading = false,
            errorMessage = errorMsg,
        )
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(5_000),
        ProfileSwitcherUiState(),
    )

    fun create(name: String, emoji: String, colorIdx: Int) {
        scope.fireAndForget(
            errorLabel = "Create profile failed",
            onError = { e -> _errorMessage.value = e.message ?: "Failed to create profile" },
        ) {
            runCatching { profileRepository.create(name, emoji, colorIdx) }
        }
    }

    fun rename(id: ProfileId, name: String) {
        scope.fireAndForget(
            errorLabel = "Rename profile failed",
            onError = { e -> _errorMessage.value = e.message ?: "Failed to rename profile" },
        ) {
            runCatching {
                val profile = profileRepository.getById(id) ?: return@runCatching Result.failure<Unit>(
                    IllegalArgumentException("Profile not found")
                )
                profileRepository.update(id, name, profile.emoji, profile.colorIdx)
            }
        }
    }

    fun delete(id: ProfileId) {
        scope.fireAndForget(
            errorLabel = "Delete profile failed",
            onError = { e -> _errorMessage.value = e.message ?: "Failed to delete profile" },
        ) {
            profileRepository.delete(id)
        }
    }

    fun switchTo(id: ProfileId) {
        scope.fireAndForget(
            errorLabel = "Switch profile failed",
            onError = { e -> _errorMessage.value = e.message ?: "Failed to switch profile" },
        ) {
            runCatching { profileRepository.switchTo(id) }
        }
    }

    fun processIntent(intent: ProfileSwitcherIntent) {
        when (intent) {
            is ProfileSwitcherIntent.DismissError -> _errorMessage.value = null
            is ProfileSwitcherIntent.Create -> create(intent.name, intent.emoji, intent.colorIdx)
            is ProfileSwitcherIntent.Rename -> rename(intent.id, intent.name)
            is ProfileSwitcherIntent.Delete -> delete(intent.id)
            is ProfileSwitcherIntent.SwitchTo -> switchTo(intent.id)
        }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
}

/** Intent sealed interface for ProfileSwitcherViewModel. */
sealed interface ProfileSwitcherIntent {
    data class Create(val name: String, val emoji: String, val colorIdx: Int) : ProfileSwitcherIntent
    data class Rename(val id: ProfileId, val name: String) : ProfileSwitcherIntent
    data class Delete(val id: ProfileId) : ProfileSwitcherIntent
    data class SwitchTo(val id: ProfileId) : ProfileSwitcherIntent
    data object DismissError : ProfileSwitcherIntent
}
