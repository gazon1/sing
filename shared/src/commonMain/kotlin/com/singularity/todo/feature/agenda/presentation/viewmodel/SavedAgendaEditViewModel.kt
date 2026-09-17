package com.singularity.todo.feature.agenda.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.core.serialization.StableJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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
    data class Editing(
        val view: SavedAgendaView,
        val editableName: String,
        val sectionCount: Int?,
        val isSaving: Boolean = false,
    ) : SavedAgendaEditState {
        val canSave: Boolean
            get() = !isSaving && editableName.isNotBlank() && editableName != view.name
    }
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

    /** One-shot UI events. */
    private val _events = MutableSharedFlow<SavedAgendaEditEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    /**
     * Writable draft name — seeded from the first loaded view, then user-controlled.
     * Initialized lazily on first view emission.
     */
    private val _draftName = MutableStateFlow("")
    private var draftSeeded = false

    private val repoFlow = deps.currentUser.scopedUserId
        .flatMapLatest { userId -> deps.repo.watchById(viewId, userId.value) }

    val state: StateFlow<SavedAgendaEditState> = combine(
        _draftName,
        repoFlow,
    ) { draftName, view ->
        when {
            view == null -> SavedAgendaEditState.NotFound
            else -> {
                // Seed draft from view.name on first emission (before user edits)
                if (!draftSeeded && draftName.isBlank()) {
                    _draftName.value = view.name
                    draftSeeded = true
                }
                val sectionCount = runCatching {
                    StableJson.decodeFromString<AgendaDefinition>(view.sectionsJson).sections.size
                }.getOrNull()
                SavedAgendaEditState.Editing(
                    view = view,
                    editableName = _draftName.value.ifBlank { view.name },
                    sectionCount = sectionCount,
                )
            }
        }
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(5_000),
        SavedAgendaEditState.Loading,
    )

    fun onIntent(intent: SavedAgendaEditIntent) {
        when (intent) {
            is SavedAgendaEditIntent.NameChanged -> {
                draftSeeded = true // user has edited, stop seeding
                _draftName.value = intent.name
            }
            is SavedAgendaEditIntent.Save -> {
                val current = state.value
                if (current !is SavedAgendaEditState.Editing || current.isSaving) return
                scope.launch {
                    val userId = deps.currentUser.scopedUserId.first().value
                    val nameToSave = _draftName.value.ifBlank { current.view.name }.trim()
                    val updated = current.view.copy(name = nameToSave, updatedAt = deps.clock.now())
                    deps.repo.upsert(updated)
                        .onSuccess { _events.emit(SavedAgendaEditEvent.SaveSuccess) }
                        .onFailure { _events.emit(SavedAgendaEditEvent.ShowError(it.message ?: "Save failed")) }
                }
            }
            is SavedAgendaEditIntent.Delete -> {
                scope.launch {
                    val userId = deps.currentUser.scopedUserId.first().value
                    deps.repo.delete(viewId, userId)
                        .onSuccess { _events.emit(SavedAgendaEditEvent.DeleteSuccess) }
                        .onFailure { _events.emit(SavedAgendaEditEvent.ShowError(it.message ?: "Delete failed")) }
                }
            }
        }
    }
}
