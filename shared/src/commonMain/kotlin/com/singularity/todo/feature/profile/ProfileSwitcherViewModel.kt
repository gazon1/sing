package com.singularity.todo.feature.profile

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.fireAndForget
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ProfileSwitcherUiState(
    val profiles: List<Profile> = emptyList(),
    val activeProfileId: ProfileId = ProfileId.default,
    val isLoading: Boolean = true,
    /** Non-null when the last mutation failed. */
    val errorMessage: String? = null,
)

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
    scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<ProfileSwitcherUiState, ProfileSwitcherIntent, Nothing>(
        initialState = ProfileSwitcherUiState(),
        scope = scope,
    ) {
    private val log = Logger.withTag("ProfileSwitcherViewModel")
    override val vmScope = scope

    private val _errorMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    init {
        // Observe profiles and active profile ID — no addCloseable(scope) needed (MviViewModel handles it)
        vmScope.launch {
            combine(
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
            }.collect { updateState { it } }
        }
    }

    override fun onIntent(intent: ProfileSwitcherIntent) {
        when (intent) {
            is ProfileSwitcherIntent.DismissError -> _errorMessage.value = null
            is ProfileSwitcherIntent.Create -> create(intent.name, intent.emoji, intent.colorIdx)
            is ProfileSwitcherIntent.Rename -> rename(intent.id, intent.name)
            is ProfileSwitcherIntent.Delete -> delete(intent.id)
            is ProfileSwitcherIntent.SwitchTo -> switchTo(intent.id)
        }
    }

    private fun create(name: String, emoji: String, colorIdx: Int) {
        vmScope.fireAndForget(
            errorLabel = "Create profile failed",
            onError = { e -> _errorMessage.value = e.message ?: "Failed to create profile" },
        ) {
            val now = Clock.now()
            profileRepository.create(
                Profile(
                    id = ProfileId.generate(),
                    name = name,
                    emoji = emoji,
                    colorIdx = colorIdx,
                    isDefault = false,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    private fun rename(id: ProfileId, name: String) {
        vmScope.fireAndForget(
            errorLabel = "Rename profile failed",
            onError = { e -> _errorMessage.value = e.message ?: "Failed to rename profile" },
        ) {
            runCatching {
                val profile = profileRepository.get(id) ?: return@runCatching Result.failure<Unit>(
                    IllegalArgumentException("Profile not found"),
                )
                profileRepository.update(profile.copy(name = name))
            }
        }
    }

    private fun delete(id: ProfileId) {
        vmScope.fireAndForget(
            errorLabel = "Delete profile failed",
            onError = { e -> _errorMessage.value = e.message ?: "Failed to delete profile" },
        ) {
            profileRepository.delete(id).onFailure { log.w { "Failed to delete profile ${id.value}: ${it.message}" } }
        }
    }

    private fun switchTo(id: ProfileId) {
        vmScope.fireAndForget(
            errorLabel = "Switch profile failed",
            onError = { e -> _errorMessage.value = e.message ?: "Failed to switch profile" },
        ) {
            profileRepository.switchTo(id)
                .onFailure { log.w { "Failed to switch to profile ${id.value}: ${it.message}" } }
        }
    }
}

/** Intent sealed interface for ProfileSwitcherViewModel. */
sealed interface ProfileSwitcherIntent : MviIntent {
    data class Create(val name: String, val emoji: String, val colorIdx: Int) : ProfileSwitcherIntent
    data class Rename(val id: ProfileId, val name: String) : ProfileSwitcherIntent
    data class Delete(val id: ProfileId) : ProfileSwitcherIntent
    data class SwitchTo(val id: ProfileId) : ProfileSwitcherIntent
    data object DismissError : ProfileSwitcherIntent
}
