@file:Suppress("NoDirectClockSystem")

package com.singularity.todo.feature.profile

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import kotlin.time.Clock
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
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<ProfileSwitcherUiState, ProfileSwitcherIntent, Nothing>(
        initialState = ProfileSwitcherUiState(),
        crashReporter = crashReporter,
        scope = scope,
    ) {
    private val log = Logger.withTag("ProfileSwitcherViewModel")

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
            }.collect { state -> setState(state) }
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
        catchTo("Failed to create profile", { msg -> _errorMessage.value = msg }) {
            val now = Clock.System.now()
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
        catchTo("Failed to rename profile", { msg -> _errorMessage.value = msg }) {
            val profile = profileRepository.get(id)
                ?: return@catchTo Result.failure<Unit>(
                    IllegalArgumentException("Profile not found"),
                )
            profileRepository.update(profile.copy(name = name))
        }
    }

    private fun delete(id: ProfileId) {
        catchTo("Failed to delete profile", { msg -> _errorMessage.value = msg }) {
            profileRepository.delete(id).onFailure { log.w { "Failed to delete profile ${id.value}" } }
        }
    }

    private fun switchTo(id: ProfileId) {
        catchTo("Failed to switch profile", { msg -> _errorMessage.value = msg }) {
            profileRepository.switchTo(id)
                .onFailure { log.w { "Failed to switch to profile ${id.value}" } }
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
