package com.singularity.todo.feature.agenda.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaViewFactory
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.feature.profile.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Dependencies for [SavedAgendaListViewModel].
 */
data class SavedAgendaListDeps(
    val repo: SavedAgendaViewsRepository,
    val currentUser: ProfileAwareCurrentUser,
    val profileRepo: ProfileRepository,
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
sealed interface SavedAgendaListIntent {
    data class Delete(val viewId: SavedAgendaViewId) : SavedAgendaListIntent
    data class CopyToProfile(val viewId: SavedAgendaViewId, val targetProfileId: ProfileId) : SavedAgendaListIntent
}

/**
 * One-shot events from [SavedAgendaListViewModel].
 * Only Delete failure needs VM involvement; routing is screen-side.
 */
sealed interface SavedAgendaListEvent {
    data class ShowError(val message: String) : SavedAgendaListEvent
    data class CopySuccess(val viewName: String, val targetProfileName: String) : SavedAgendaListEvent
}

/**
 * ViewModel for the saved agenda views list screen.
 * No runtime parameters — injected via explicit `viewModel { }` block in AgendaDiModule.
 * Note: `viewModelOf` does not work with multi-arg constructors — Koin cannot provide
 * `CoroutineScope` as a bean. Use `viewModel { SavedAgendaListViewModel(get()) }` instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedAgendaListViewModel(
    private val deps: SavedAgendaListDeps,
    private val scope: CoroutineScope,
) : ViewModel() {

    /** Production/Koin constructor — defaults scope to Main-immediate. */
    constructor(
        deps: SavedAgendaListDeps,
    ) : this(
        deps = deps,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    /** Delete failure events — routing (ViewSelected, CreateNew) is screen-side. */
    private val _events = MutableSharedFlow<SavedAgendaListEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    val state: StateFlow<SavedAgendaListState> = deps.currentUser.scopedUserId
        .flatMapLatest { userId -> deps.repo.watchAll(userId.value) }
        .map { views -> SavedAgendaListState.Loaded(views) }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(5_000),
            SavedAgendaListState.Loading,
        )

    fun onIntent(intent: SavedAgendaListIntent) {
        when (intent) {
            is SavedAgendaListIntent.Delete -> with(intent) {
                scope.launch {
                    val userId = deps.currentUser.scopedUserId.value.value
                    deps.repo.delete(viewId, userId)
                        .onFailure { _events.emit(SavedAgendaListEvent.ShowError(it.message ?: "Delete failed")) }
                }
            }

            is SavedAgendaListIntent.CopyToProfile -> with(intent) {
                scope.launch {
                    val sourceUserId = deps.currentUser.scopedUserId.value.value
                    val sourceView = deps.repo.watchById(viewId, sourceUserId).first()
                    if (sourceView == null) {
                        _events.emit(SavedAgendaListEvent.ShowError("View not found"))
                        return@launch
                    }
                    val targetProfile = deps.profileRepo.getById(targetProfileId)
                    if (targetProfile == null) {
                        _events.emit(SavedAgendaListEvent.ShowError("Profile not found"))
                        return@launch
                    }
                    val now = Clock.now()
                    val copy = SavedAgendaViewFactory.duplicateForProfile(
                        source = sourceView,
                        targetUserId = targetProfile.id.value,
                        now = now,
                    )
                    deps.repo.upsert(copy)
                        .onSuccess {
                            _events.emit(SavedAgendaListEvent.CopySuccess(sourceView.name.ifBlank { "Untitled" }, targetProfile.name))
                        }
                        .onFailure {
                            _events.emit(SavedAgendaListEvent.ShowError(it.message ?: "Copy failed"))
                        }
                }
            }
        }
    }
}
