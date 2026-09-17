package com.singularity.todo.feature.agenda.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
}

/**
 * One-shot events from [SavedAgendaListViewModel].
 * Only Delete failure needs VM involvement; routing is screen-side.
 */
sealed interface SavedAgendaListEvent {
    data class ShowError(val message: String) : SavedAgendaListEvent
}

/**
 * ViewModel for the saved agenda views list screen.
 * No runtime parameters — injected via [viewModelOf].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedAgendaListViewModel(
    private val deps: SavedAgendaListDeps,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

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
            is SavedAgendaListIntent.Delete -> {
                scope.launch {
                    val userId = deps.currentUser.scopedUserId.value.value
                    deps.repo.delete(intent.viewId, userId)
                        .onFailure { _events.emit(SavedAgendaListEvent.ShowError(it.message ?: "Delete failed")) }
                }
            }
        }
    }
}
