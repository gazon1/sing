package com.singularity.todo.feature.profile

import androidx.lifecycle.ViewModel
import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.fireAndForget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {
    private val log = Logger.withTag("ProfileSwitcherViewModel")

    init {
        addCloseable(scope)
    }

    private val _errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ProfileSwitcherUiState> = combine(
        profileRepository.observeAll(),
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
            val now = com.singularity.todo.core.platform.Clock.now()
            profileRepository.create(Profile(
                id = ProfileId.generate(),
                name = name,
                emoji = emoji,
                colorIdx = colorIdx,
                isDefault = false,
                createdAt = now,
                updatedAt = now,
            ))
        }
    }

    fun rename(id: ProfileId, name: String) {
        scope.fireAndForget(
            errorLabel = "Rename profile failed",
            onError = { e -> _errorMessage.value = e.message ?: "Failed to rename profile" },
        ) {
            runCatching {
                val profile = profileRepository.get(id) ?: return@runCatching Result.failure<Unit>(
                    IllegalArgumentException("Profile not found")
                )
                profileRepository.update(profile.copy(name = name))
            }
        }
    }

    fun delete(id: ProfileId) {
        scope.fireAndForget(
            errorLabel = "Delete profile failed",
            onError = { e -> _errorMessage.value = e.message ?: "Failed to delete profile" },
        ) {
            profileRepository.delete(id).onFailure { log.w { "Failed to delete profile ${id.value}: ${it.message}" } }
        }
    }

    fun switchTo(id: ProfileId) {
        scope.fireAndForget(
            errorLabel = "Switch profile failed",
            onError = { e -> _errorMessage.value = e.message ?: "Failed to switch profile" },
        ) {
            profileRepository.switchTo(id).onFailure { log.w { "Failed to switch to profile ${id.value}: ${it.message}" } }
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
}

/** Intent sealed interface for ProfileSwitcherViewModel. */
sealed interface ProfileSwitcherIntent {
    data class Create(val name: String, val emoji: String, val colorIdx: Int) : ProfileSwitcherIntent
    data class Rename(val id: ProfileId, val name: String) : ProfileSwitcherIntent
    data class Delete(val id: ProfileId) : ProfileSwitcherIntent
    data class SwitchTo(val id: ProfileId) : ProfileSwitcherIntent
    data object DismissError : ProfileSwitcherIntent
}
