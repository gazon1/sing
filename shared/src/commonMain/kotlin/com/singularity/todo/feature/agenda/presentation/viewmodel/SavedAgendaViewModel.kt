package com.singularity.todo.feature.agenda.presentation.viewmodel

import androidx.lifecycle.ViewModel
import co.touchlab.kermit.Logger
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.core.serialization.StableJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Dependencies for [SavedAgendaViewModel].
 */
data class SavedAgendaDeps(
    val repo: SavedAgendaViewsRepository,
    val currentUser: ProfileAwareCurrentUser,
    val clock: Clock = Clock,
    val log: Logger,
)

/**
 * Editable draft state — single source of truth for name/sections.
 * Mutated directly via typed methods. No flow magic, no combine.
 */
class DraftState(initial: Draft = Draft.empty()) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<Draft> = _state.asStateFlow()
    val current: Draft get() = _state.value

    /** Idempotent seed — only sets if not already initialized. */
    fun seed(draft: Draft) {
        if (_state.value.initialized) return
        _state.value = draft
    }

    fun setName(name: String) { _state.update { it.copy(name = name) } }
    fun reorderSections(sections: List<Section>) { _state.update { it.copy(sections = sections) } }
    fun addSection(template: Section, position: Int) {
        _state.update { draft ->
            val sections = draft.sections.toMutableList().apply {
                add(position.coerceIn(0, size), template)
            }
            draft.copy(sections = sections)
        }
    }
    fun removeSection(index: Int) {
        _state.update { draft ->
            if (index < 0 || index >= draft.sections.size) return@update draft
            val sections = draft.sections.toMutableList().apply { removeAt(index) }
            draft.copy(sections = sections)
        }
    }
}

data class Draft(
    val name: String,
    val sections: List<Section>,
    val originalName: String,
    val originalSections: List<Section>,
    val initialized: Boolean = false,
) {
    val isDirty: Boolean get() = name != originalName || sections != originalSections
    companion object {
        fun empty() = Draft("", emptyList(), "", emptyList(), false)
    }
}

sealed interface SavedAgendaViewState {
    data object Loading : SavedAgendaViewState
    data class Editing(
        val view: SavedAgendaView?,
        val draft: Draft,
        val sectionCount: Int?,
        val isSaving: Boolean = false,
        val decodeError: Boolean = false,
    ) : SavedAgendaViewState {
        val canSave: Boolean get() = draft.initialized && !isSaving && draft.name.isNotBlank() && draft.isDirty && !decodeError
    }
    data object NotFound : SavedAgendaViewState
}

sealed interface SavedAgendaIntent {
    data class NameChanged(val name: String) : SavedAgendaIntent
    data class SectionsReordered(val sections: List<Section>) : SavedAgendaIntent
    data class SectionAdded(val template: Section, val position: Int) : SavedAgendaIntent
    data class SectionRemoved(val index: Int) : SavedAgendaIntent
    data object Save : SavedAgendaIntent
    data object Delete : SavedAgendaIntent
}

sealed interface SavedAgendaEvent {
    data object SaveSuccess : SavedAgendaEvent
    data object DeleteSuccess : SavedAgendaEvent
    data class ShowError(val message: String) : SavedAgendaEvent
}

/**
 * ViewModel for editing or creating a saved agenda view.
 *
 * Testability: pass a custom [CoroutineScope] (e.g. test's `backgroundScope`) to the
 * 4-arg constructor — it will be used instead of viewModelScope, so `advanceUntilIdle()`
 * flushes the VM's coroutines in unit tests.
 *
 * Production: Koin uses the 3-arg constructor which delegates to the 4-arg one with
 * a dedicated scope tied to a SupervisorJob.
 */
class SavedAgendaViewModel(
    private val deps: SavedAgendaDeps,
    private val mode: SavedAgendaScreenMode,
    private val seedStore: SavedAgendaSeedStore,
    private val scope: CoroutineScope,
) : ViewModel() {

    /** Production constructor — Koin uses this. */
    constructor(
        deps: SavedAgendaDeps,
        mode: SavedAgendaScreenMode,
        seedStore: SavedAgendaSeedStore,
    ) : this(
        deps = deps,
        mode = mode,
        seedStore = seedStore,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val _events = MutableSharedFlow<SavedAgendaEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()
    val draftState = DraftState()
    private val _state = MutableStateFlow<SavedAgendaViewState>(SavedAgendaViewState.Loading)
    val state: StateFlow<SavedAgendaViewState> = _state.asStateFlow()

    init {
        scope.launch {
            when (val m = mode) {
                is SavedAgendaScreenMode.Edit -> initEditMode(m)
                is SavedAgendaScreenMode.Create -> initCreateMode(m)
            }
        }
    }

    private suspend fun initEditMode(mode: SavedAgendaScreenMode.Edit) {
        val userId = deps.currentUser.scopedUserId.first().value
        val view = deps.repo.watchById(mode.viewId, userId).first()
        if (view == null) {
            _state.value = SavedAgendaViewState.NotFound
            return
        }
        val sections = decodeSections(view.sectionsJson)
        val draft = Draft(view.name, sections ?: emptyList(), view.name, sections ?: emptyList(), true)
        draftState.seed(draft)
        _state.value = SavedAgendaViewState.Editing(view, draftState.current, sections?.size, decodeError = sections == null)
    }

    private fun initCreateMode(mode: SavedAgendaScreenMode.Create) {
        val seed = seedStore.consumeSeed() ?: mode.seed
        val draft = Draft(seed.title, seed.sections, seed.title, seed.sections, true)
        draftState.seed(draft)
        _state.value = SavedAgendaViewState.Editing(null, draftState.current, seed.sections.size)
    }

    fun onIntent(intent: SavedAgendaIntent) {
        when (intent) {
            is SavedAgendaIntent.NameChanged -> { draftState.setName(intent.name); emitEditingState() }
            is SavedAgendaIntent.SectionsReordered -> { draftState.reorderSections(intent.sections); emitEditingState() }
            is SavedAgendaIntent.SectionAdded -> { draftState.addSection(intent.template, intent.position); emitEditingState() }
            is SavedAgendaIntent.SectionRemoved -> { draftState.removeSection(intent.index); emitEditingState() }
            is SavedAgendaIntent.Save -> onSave()
            is SavedAgendaIntent.Delete -> onDelete()
        }
    }

    private fun emitEditingState() {
        val draft = draftState.current
        val current = _state.value
        _state.value = SavedAgendaViewState.Editing(
            view = (current as? SavedAgendaViewState.Editing)?.view,
            draft = draft,
            sectionCount = draft.sections.size,
        )
    }

    private fun onSave() {
        val current = _state.value
        if (current !is SavedAgendaViewState.Editing || current.isSaving || !current.canSave) return
        _state.value = current.copy(isSaving = true)
        scope.launch {
            val userId = deps.currentUser.scopedUserId.first().value
            val draft = draftState.current
            val nameToSave = draft.name.trim()
            val sectionsJson = StableJson.encodeToString(
                AgendaDefinition.serializer(),
                AgendaDefinition(nameToSave, draft.sections),
            )
            val now = deps.clock.now()
            when (mode) {
                is SavedAgendaScreenMode.Edit -> {
                    val updated = current.view!!.copy(name = nameToSave, sectionsJson = sectionsJson, updatedAt = now)
                    deps.repo.upsert(updated).fold(
                        onSuccess = { _events.emit(SavedAgendaEvent.SaveSuccess) },
                        onFailure = { _events.emit(SavedAgendaEvent.ShowError(it.message ?: "Save failed")) },
                    )
                }
                is SavedAgendaScreenMode.Create -> {
                    val newView = SavedAgendaView(SavedAgendaViewId.generate(), userId, nameToSave, sectionsJson, now, now)
                    deps.repo.upsert(newView).fold(
                        onSuccess = { _events.emit(SavedAgendaEvent.SaveSuccess) },
                        onFailure = { _events.emit(SavedAgendaEvent.ShowError(it.message ?: "Save failed")) },
                    )
                }
            }
        }
    }

    private fun onDelete() {
        val viewId = (mode as? SavedAgendaScreenMode.Edit)?.viewId ?: return
        scope.launch {
            val userId = deps.currentUser.scopedUserId.first().value
            deps.repo.delete(viewId, userId).fold(
                onSuccess = { _events.emit(SavedAgendaEvent.DeleteSuccess) },
                onFailure = { _events.emit(SavedAgendaEvent.ShowError(it.message ?: "Delete failed")) },
            )
        }
    }

    private fun decodeSections(json: String?): List<Section>? =
        if (json == null) null
        else runCatching { StableJson.decodeFromString<AgendaDefinition>(json).sections }
            .onFailure { e -> deps.log.w("agenda decode failed: ${e.message}") }
            .getOrNull()
}
