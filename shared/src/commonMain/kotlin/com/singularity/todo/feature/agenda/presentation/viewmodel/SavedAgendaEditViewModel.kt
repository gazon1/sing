package com.singularity.todo.feature.agenda.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Dependencies for [SavedAgendaEditViewModel].
 */
data class SavedAgendaEditDeps(
    val repo: SavedAgendaViewsRepository,
    val currentUser: ProfileAwareCurrentUser,
    val clock: Clock = Clock,
)

/**
 * UI state for the saved agenda view edit screen.
 */
sealed interface SavedAgendaEditState {
    data object Loading : SavedAgendaEditState
    data class Editing(val view: SavedAgendaView, val editableName: String) : SavedAgendaEditState
    data object NotFound : SavedAgendaEditState
}

/**
 * User intents on the saved agenda view edit screen.
 */
sealed interface SavedAgendaEditIntent {
    data class NameChanged(val name: String) : SavedAgendaEditIntent
    data object Save : SavedAgendaEditIntent
    data object Delete : SavedAgendaEditIntent
}

/**
 * One-shot events from [SavedAgendaEditViewModel].
 */
sealed interface SavedAgendaEditEvent {
    data object SaveSuccess : SavedAgendaEditEvent
    data object DeleteSuccess : SavedAgendaEditEvent
    data class ShowError(val message: String) : SavedAgendaEditEvent
}

/**
 * ViewModel for editing a single saved agenda view.
 * Runtime parameter: [viewId] — the ID of the view to edit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedAgendaEditViewModel(
    private val deps: SavedAgendaEditDeps,
    private val viewId: SavedAgendaViewId,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    /** The editable name — synced from loaded view on first load via [init]. */
    private val _editableName = MutableStateFlow("")
    val editableName: StateFlow<String> = _editableName

    /** One-shot UI events. */
    private val _events = MutableSharedFlow<SavedAgendaEditEvent>()
    val events = _events.asSharedFlow()

    /**
     * Initialize [_editableName] from the loaded view — runs once on VM creation.
     * Uses [first] to get a single snapshot without subscribing indefinitely.
     */
    init {
        scope.launch {
            val userId = deps.currentUser.current.value
            deps.repo.watchById(viewId, userId).first()?.let { view ->
                if (_editableName.value.isEmpty()) {
                    _editableName.value = view.name
                }
            }
        }
    }

    /**
     * State — watches the saved view and pairs it with the current [_editableName].
     * Uses [flatMapLatest] + [map] so the state updates reactively when either the
     * view changes OR the user edits the name field.
     */
    val state: StateFlow<SavedAgendaEditState> = deps.currentUser.scopedUserId
        .flatMapLatest { userId -> deps.repo.watchById(viewId, userId.value) }
        .map { view ->
            if (view == null) {
                SavedAgendaEditState.NotFound
            } else {
                SavedAgendaEditState.Editing(view = view, editableName = _editableName.value)
            }
        }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(5_000),
            SavedAgendaEditState.Loading,
        )

    fun onIntent(intent: SavedAgendaEditIntent) {
        when (intent) {
            is SavedAgendaEditIntent.NameChanged -> {
                _editableName.value = intent.name
            }

            is SavedAgendaEditIntent.Save -> {
                scope.launch {
                    val currentState = state.value
                    if (currentState is SavedAgendaEditState.Editing) {
                        val updated = currentState.view.copy(
                            name = _editableName.value.trim(),
                            updatedAt = deps.clock.now(),
                        )
                        deps.repo.upsert(updated)
                            .onSuccess { _events.emit(SavedAgendaEditEvent.SaveSuccess) }
                            .onFailure { _events.emit(SavedAgendaEditEvent.ShowError(it.message ?: "Save failed")) }
                    }
                }
            }

            is SavedAgendaEditIntent.Delete -> {
                scope.launch {
                    val userId = deps.currentUser.current.value
                    deps.repo.delete(viewId, userId)
                        .onSuccess { _events.emit(SavedAgendaEditEvent.DeleteSuccess) }
                        .onFailure { _events.emit(SavedAgendaEditEvent.ShowError(it.message ?: "Delete failed")) }
                }
            }
        }
    }
}
