package com.singularity.todo.feature.agenda.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.model.Section
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Dependencies for [SavedAgendaViewModel].
 */
data class SavedAgendaDeps(
    val repo: SavedAgendaViewsRepository,
    val currentUser: ProfileAwareCurrentUser,
    val clock: Clock = Clock,
)

/**
 * Draft state for the saved agenda view editor.
 *
 * Tracks the editable [name] and [sections] along with their original values
 * so that [isDirty] can be derived without a separate flag.
 */
data class Draft(
    val name: String,
    val sections: List<Section>,
    val originalName: String,
    val originalSections: List<Section>,
    val initialized: Boolean = false,
) {
    val isDirty: Boolean
        get() = name != originalName || sections != originalSections
}

/**
 * UI state for the saved agenda view edit screen.
 */
sealed interface SavedAgendaViewState {
    data object Loading : SavedAgendaViewState

    data class Editing(
        val view: SavedAgendaView?,
        val draft: Draft,
        val sectionCount: Int?,
        val isSaving: Boolean = false,
        val decodeError: Boolean = false,
    ) : SavedAgendaViewState {
        val canSave: Boolean
            get() = draft.initialized && !isSaving && draft.name.isNotBlank() && draft.isDirty && !decodeError
    }

    /** Only reachable in Edit mode when the view does not exist. */
    data object NotFound : SavedAgendaViewState
}

/**
 * User intents on the saved agenda view edit screen.
 */
sealed interface SavedAgendaIntent {
    data class NameChanged(val name: String) : SavedAgendaIntent
    data class SectionsReordered(val sections: List<Section>) : SavedAgendaIntent
    data object Save : SavedAgendaIntent
    data object Delete : SavedAgendaIntent
}

/**
 * One-shot events from [SavedAgendaViewModel].
 */
sealed interface SavedAgendaEvent {
    data object SaveSuccess : SavedAgendaEvent
    data object DeleteSuccess : SavedAgendaEvent
    data class ShowError(val message: String) : SavedAgendaEvent
}

/**
 * ViewModel for editing or creating a saved agenda view.
 * Runtime parameter: [mode] — either [SavedAgendaScreenMode.Edit] or [SavedAgendaScreenMode.Create].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedAgendaViewModel(
    private val deps: SavedAgendaDeps,
    private val mode: SavedAgendaScreenMode,
    private val seedStore: SavedAgendaSeedStore,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    /** One-shot UI events. */
    private val _events = MutableSharedFlow<SavedAgendaEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    /**
     * Writable draft state. In Edit mode it is seeded from the loaded view;
     * in Create mode it is seeded from [SavedAgendaSeedStore].
     */
    private val _draft = MutableStateFlow(Draft(name = "", sections = emptyList(), originalName = "", originalSections = emptyList()))

    /**
     * Source of truth for the persisted view.
     * - Edit mode: watches the repository for changes to the target view.
     * - Create mode: emits null (no persisted view yet).
     */
    private val repoFlow = when (mode) {
        is SavedAgendaScreenMode.Edit -> deps.currentUser.scopedUserId
            .flatMapLatest { userId -> deps.repo.watchById(mode.viewId, userId.value) }
            .map { view -> view to decodeSections(view?.sectionsJson) }
        is SavedAgendaScreenMode.Create -> flowOf(null to null)
    }

    val state: StateFlow<SavedAgendaViewState> = combine(
        _draft,
        repoFlow,
    ) { draft, repoResult ->
        val (view, decodedSections) = repoResult

        when {
            // Edit mode: view not found
            mode is SavedAgendaScreenMode.Edit && view == null -> SavedAgendaViewState.NotFound

            // Create mode: seed draft from SeedStore on first emission
            mode is SavedAgendaScreenMode.Create && !draft.initialized -> {
                val seed = seedStore.consumeSeed() ?: mode.seed
                val seeded = Draft(
                    name = seed.title,
                    sections = seed.sections,
                    originalName = seed.title,
                    originalSections = seed.sections,
                    initialized = true,
                )
                _draft.value = seeded
                SavedAgendaViewState.Editing(
                    view = null,
                    draft = seeded,
                    sectionCount = seed.sections.size,
                    decodeError = false,
                )
            }

            // Edit mode: view loaded — seed draft on first emission
            mode is SavedAgendaScreenMode.Edit && view != null && !draft.initialized -> {
                val sections = decodedSections ?: return@combine SavedAgendaViewState.Editing(
                    view = view,
                    draft = draft.copy(initialized = true),
                    sectionCount = null,
                    decodeError = true,
                )
                val seeded = Draft(
                    name = view.name,
                    sections = sections,
                    originalName = view.name,
                    originalSections = sections,
                    initialized = true,
                )
                _draft.value = seeded
                SavedAgendaViewState.Editing(
                    view = view,
                    draft = seeded,
                    sectionCount = sections.size,
                    decodeError = false,
                )
            }

            // Subsequent emissions: update section count if decode succeeded
            else -> SavedAgendaViewState.Editing(
                view = view,
                draft = draft,
                sectionCount = decodedSections?.size,
                decodeError = decodedSections == null && view != null,
            )
        }
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(5_000),
        SavedAgendaViewState.Loading,
    )

    fun onIntent(intent: SavedAgendaIntent) {
        when (intent) {
            is SavedAgendaIntent.NameChanged -> {
                _draft.value = _draft.value.copy(name = intent.name)
            }

            is SavedAgendaIntent.SectionsReordered -> {
                _draft.value = _draft.value.copy(sections = intent.sections)
            }

            is SavedAgendaIntent.Save -> {
                val current = state.value
                if (current !is SavedAgendaViewState.Editing || current.isSaving || !current.canSave) return
                scope.launch {
                    val userId = deps.currentUser.scopedUserId.first().value
                    val nameToSave = current.draft.name.trim()
                    val definitionToSave = AgendaDefinition(
                        title = nameToSave,
                        sections = current.draft.sections,
                    )
                    val sectionsJson = StableJson.encodeToString(AgendaDefinition.serializer(), definitionToSave)
                    val now = deps.clock.now()

                    when (mode) {
                        is SavedAgendaScreenMode.Edit -> {
                            val updated = current.view!!.copy(
                                name = nameToSave,
                                sectionsJson = sectionsJson,
                                updatedAt = now,
                            )
                            deps.repo.upsert(updated)
                                .onSuccess { _events.emit(SavedAgendaEvent.SaveSuccess) }
                                .onFailure { _events.emit(SavedAgendaEvent.ShowError(it.message ?: "Save failed")) }
                        }

                        is SavedAgendaScreenMode.Create -> {
                            val id = SavedAgendaViewId.generate()
                            val newView = SavedAgendaView(
                                id = id,
                                userId = userId,
                                name = nameToSave,
                                sectionsJson = sectionsJson,
                                createdAt = now,
                                updatedAt = now,
                            )
                            deps.repo.upsert(newView)
                                .onSuccess { _events.emit(SavedAgendaEvent.SaveSuccess) }
                                .onFailure { _events.emit(SavedAgendaEvent.ShowError(it.message ?: "Save failed")) }
                        }
                    }
                }
            }

            is SavedAgendaIntent.Delete -> {
                val current = state.value
                if (current !is SavedAgendaViewState.Editing) return
                val viewId = when (mode) {
                    is SavedAgendaScreenMode.Edit -> mode.viewId
                    is SavedAgendaScreenMode.Create -> return // Nothing to delete in Create mode
                }
                scope.launch {
                    val userId = deps.currentUser.scopedUserId.first().value
                    deps.repo.delete(viewId, userId)
                        .onSuccess { _events.emit(SavedAgendaEvent.DeleteSuccess) }
                        .onFailure { _events.emit(SavedAgendaEvent.ShowError(it.message ?: "Delete failed")) }
                }
            }
        }
    }

    private fun decodeSections(json: String?): List<Section>? {
        if (json == null) return null
        return runCatching {
            StableJson.decodeFromString<AgendaDefinition>(json).sections
        }.getOrNull()
    }
}
