package com.singularity.todo.feature.agenda.presentation.viewmodel

import androidx.compose.runtime.Stable
import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaViewFactory
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Dependencies for [SavedAgendaViewModel].
 */
data class SavedAgendaDeps(
    val repo: SavedAgendaViewsRepository,
    val clock: kotlin.time.Clock = kotlin.time.Clock.System,
    val log: Logger,
)

/**
 * Editable draft state — single source of truth for name/sections.
 * Mutated directly via typed methods. No flow magic, no combine.
 */
@Stable
class SavedAgendaDraftState {

    private var draft: Draft = Draft.empty()

    val state: Draft get() = draft

    fun seed(value: Draft) {
        if (draft.initialized) return
        draft = value
    }

    fun setName(name: String) = update { it.copy(name = name) }

    fun reorderSections(sections: List<Section>) = update { it.copy(sections = sections) }

    fun addSection(template: Section, position: Int) = update { draft ->
        val sections = draft.sections.toMutableList()
            .apply {
                add(position.coerceIn(0, size), template)
            }
        draft.copy(sections = sections)
    }

    fun removeSection(index: Int) = update { draft ->
        if (index < 0 || index >= draft.sections.size) return@update draft
        val sections = draft.sections.toMutableList()
            .apply { removeAt(index) }
        draft.copy(sections = sections)
    }

    /** Marks the current draft as saved — resets originalName/originalSections so isDirty becomes false. */
    fun markSaved() = update { it.copy(originalName = it.name, originalSections = it.sections) }

    private fun update(reducer: (Draft) -> Draft) {
        draft = reducer(draft)
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
        val canSave: Boolean get() = draft.initialized && !isSaving && draft.name.isNotBlank() && draft.isDirty &&
            !decodeError
    }

    data object NotFound : SavedAgendaViewState
}

sealed interface SavedAgendaIntent : MviIntent {
    data class NameChanged(val name: String) : SavedAgendaIntent
    data class SectionsReordered(val sections: List<Section>) : SavedAgendaIntent
    data class SectionAdded(val template: Section, val position: Int) : SavedAgendaIntent
    data class SectionRemoved(val index: Int) : SavedAgendaIntent
    data object Save : SavedAgendaIntent
    data object Delete : SavedAgendaIntent
}

sealed interface SavedAgendaEvent : MviEvent {
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
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<SavedAgendaViewState, SavedAgendaIntent, SavedAgendaEvent>(
        initialState = SavedAgendaViewState.Loading,
        scope = scope,
    ) {

    val draftState = SavedAgendaDraftState()

    init {
        scope.launch {
            when (val m = mode) {
                is SavedAgendaScreenMode.Edit -> initEditMode(m)
                is SavedAgendaScreenMode.Create -> initCreateMode(m)
            }
        }
    }

    private suspend fun initEditMode(mode: SavedAgendaScreenMode.Edit) {
        val view = deps.repo.observe(mode.viewId)
            .first()
        if (view == null) {
            setState(SavedAgendaViewState.NotFound)
            return
        }
        val sections = decodeSections(view.sectionsJson)
        val draft = Draft(
            view.name,
            sections
                ?: emptyList(),
            view.name,
            sections
                ?: emptyList(),
            true,
        )
        draftState.seed(draft)
        setState(
            SavedAgendaViewState.Editing(
                view,
                draftState.state,
                sections?.size,
                decodeError = sections == null,
            ),
        )
    }

    private fun initCreateMode(mode: SavedAgendaScreenMode.Create) {
        val seed = seedStore.consumeSeed()
            ?: mode.seed
        val draft = Draft(seed.title, seed.sections, seed.title, seed.sections, true)
        draftState.seed(draft)
        setState(
            SavedAgendaViewState.Editing(null, draftState.state, seed.sections.size),
        )
    }

    override fun onIntent(intent: SavedAgendaIntent) {
        when (intent) {
            is SavedAgendaIntent.NameChanged -> with(intent) {
                draftState.setName(name)
                emitEditingState()
            }

            is SavedAgendaIntent.SectionsReordered -> with(intent) {
                draftState.reorderSections(sections)
                emitEditingState()
            }

            is SavedAgendaIntent.SectionAdded -> with(intent) {
                draftState.addSection(template, position)
                emitEditingState()
            }

            is SavedAgendaIntent.SectionRemoved -> with(intent) {
                draftState.removeSection(index)
                emitEditingState()
            }

            is SavedAgendaIntent.Save -> onSave()

            is SavedAgendaIntent.Delete -> onDelete()
        }
    }

    private fun emitEditingState() {
        val current = currentState
        setState(
            SavedAgendaViewState.Editing(
                view = (current as? SavedAgendaViewState.Editing)?.view,
                draft = draftState.state,
                sectionCount = draftState.state.sections.size,
            ),
        )
    }

    private fun onSave() {
        val current = currentState
        if (current !is SavedAgendaViewState.Editing || current.isSaving || !current.canSave) return
        setState(current.copy(isSaving = true))
        scope.launch {
            val draft = draftState.state
            val nameToSave = draft.name.trim()
            val sectionsJson = StableJson.encodeToString(
                AgendaDefinition.serializer(),
                AgendaDefinition(nameToSave, draft.sections),
            )
            val now = deps.clock.now()
            when (mode) {
                is SavedAgendaScreenMode.Edit -> {
                    val updated = SavedAgendaViewFactory.update(current.view!!, nameToSave, sectionsJson, now)
                    deps.repo.upsert(updated)
                        .fold(
                            onSuccess = {
                                draftState.markSaved()
                                emit(SavedAgendaEvent.SaveSuccess)
                            },
                            onFailure = {
                                emit(
                                    SavedAgendaEvent.ShowError(
                                        it.toMessage("Save failed"),
                                    ),
                                )
                            },
                        )
                }

                is SavedAgendaScreenMode.Create -> {
                    // Anonymous sentinel — repo stamps ambient userId on insert
                    val newView = SavedAgendaViewFactory.create(UserId.anonymous, nameToSave, sectionsJson, now)
                    deps.repo.upsert(newView)
                        .fold(
                            onSuccess = { emit(SavedAgendaEvent.SaveSuccess) },
                            onFailure = {
                                emit(
                                    SavedAgendaEvent.ShowError(
                                        it.toMessage("Save failed"),
                                    ),
                                )
                            },
                        )
                }
            }
        }
    }

    private fun onDelete() {
        val viewId = (mode as? SavedAgendaScreenMode.Edit)?.viewId
            ?: return
        scope.launch {
            deps.repo.delete(viewId)
                .fold(
                    onSuccess = { emit(SavedAgendaEvent.DeleteSuccess) },
                    onFailure = {
                        emit(
                            SavedAgendaEvent.ShowError(
                                it.message
                                    ?: "Delete failed",
                            ),
                        )
                    },
                )
        }
    }

    private fun decodeSections(json: String?): List<Section>? = if (json == null) {
        null
    } else {
        runCatching {
            StableJson.decodeFromString<AgendaDefinition>(
                json,
            ).sections
        }.onFailure { e -> deps.log.w("agenda decode failed: ${e.message}") }
            .getOrNull()
    }
}
