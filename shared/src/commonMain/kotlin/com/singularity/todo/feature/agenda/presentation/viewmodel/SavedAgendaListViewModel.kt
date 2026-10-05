
package com.singularity.todo.feature.agenda.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import com.singularity.todo.feature.profile.scopedUserIdFor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Dependencies for [SavedAgendaListViewModel].
 */
data class SavedAgendaListDeps(
    val repo: SavedAgendaViewsRepository,
    val profileRepo: ProfileRepository,
    val currentUser: ProfileAwareCurrentUser,
)

/**
 * UI state for the saved agenda views list screen.
 */
sealed interface SavedAgendaListState {
    data object Loading : SavedAgendaListState
    data class Loaded(val views: List<SavedAgendaView>) : SavedAgendaListState
}

/**
 * User intents on the saved agenda views list screen.
 */
sealed interface SavedAgendaListIntent : MviIntent {
    data class Delete(val viewId: SavedAgendaViewId) : SavedAgendaListIntent
    data class CopyToProfile(val viewId: SavedAgendaViewId, val targetProfileId: ProfileId) : SavedAgendaListIntent
}

/**
 * One-shot events from [SavedAgendaListViewModel].
 * Only Delete failure needs VM involvement; routing is screen-side.
 */
sealed interface SavedAgendaListEvent : MviEvent {
    data class ShowError(val message: String) : SavedAgendaListEvent
    data class CopySuccess(val viewName: String, val targetProfileName: String) : SavedAgendaListEvent
}

/**
 * ViewModel for the saved agenda views list screen.
 * No runtime parameters — injected via explicit `viewModel { }` block in AgendaDiModule.
 * Note: `viewModelOf` does not work here — two-constructor testable-VM pattern creates
 * constructor ambiguity. Use explicit `viewModel { }` block instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedAgendaListViewModel(
    private val deps: SavedAgendaListDeps,
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    private val scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<SavedAgendaListState, SavedAgendaListIntent, SavedAgendaListEvent>(
        initialState = SavedAgendaListState.Loading,
        crashReporter = crashReporter,
        scope = scope,
    ) {

    init {
        scope.launch {
            deps.repo.observeAll()
                .map { views -> SavedAgendaListState.Loaded(views) }
                .collect { loaded -> setState(loaded) }
        }
    }

    override fun onIntent(intent: SavedAgendaListIntent) {
        when (intent) {
            is SavedAgendaListIntent.Delete -> with(intent) {
                emitError("Delete failed", SavedAgendaListEvent::ShowError) {
                    deps.repo.delete(viewId)
                }
            }

            is SavedAgendaListIntent.CopyToProfile -> with(intent) {
                scope.launch {
                    val sourceView = deps.repo.observe(viewId)
                        .first()
                    if (sourceView == null) {
                        emit(SavedAgendaListEvent.ShowError("View not found"))
                        return@launch
                    }
                    val targetProfile = deps.profileRepo.get(targetProfileId)
                    if (targetProfile == null) {
                        emit(SavedAgendaListEvent.ShowError("Profile not found"))
                        return@launch
                    }
                    // The copy must land in the TARGET profile's data namespace,
                    // so it needs the profile's SCOPED userId (default profile →
                    // raw userId, else "{profileId}/{raw}") — not the profile
                    // row's id. [scopedUserIdFor] is the shared mapping used by
                    // [ProfileAwareCurrentUser] itself.
                    val targetScopedId = scopedUserIdFor(
                        profileId = targetProfile.id,
                        userId = deps.currentUser.userId.value,
                    )
                    deps.repo.duplicateForProfile(sourceView, targetScopedId.value)
                        .onSuccess {
                            emit(
                                SavedAgendaListEvent.CopySuccess(
                                    sourceView.name.ifBlank { "Untitled" },
                                    targetProfile.name,
                                ),
                            )
                        }
                        .onFailure {
                            emit(
                                SavedAgendaListEvent.ShowError(
                                    it.toMessage("Copy failed"),
                                ),
                            )
                        }
                }
            }
        }
    }
}
