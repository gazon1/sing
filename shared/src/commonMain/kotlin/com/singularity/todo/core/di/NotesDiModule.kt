package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase
import com.singularity.todo.feature.notes.NotesRepository
import com.singularity.todo.feature.notes.RoomNotesRepository
import com.singularity.todo.feature.notes.domain.editor.NoteAiController
import com.singularity.todo.feature.notes.domain.editor.improveNoteLambda
import com.singularity.todo.feature.notes.presentation.viewmodel.NoteEditor
import com.singularity.todo.feature.notes.presentation.viewmodel.NotePreview
import com.singularity.todo.feature.notes.presentation.viewmodel.NotesListViewModel
import com.singularity.todo.feature.search.InternalLinkRepository
import com.singularity.todo.feature.search.InternalLinkRepositoryImpl
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Notes feature DI: repositories, ViewModels, ports.
 *
 * Three ViewModels split by lifecycle scope:
 * - [NotesListViewModel] — list, filter, sort, multi-select, swipe actions
 * - [NoteEditor] — editing session, autosave, AI improve
 * - [NotePreview] — read-only view of a single note and its backlinks
 */
fun notesModule(): org.koin.core.module.Module = module {
    // ─── Repository ─────────────────────────────────────────────────────

    single<NotesRepository> { RoomNotesRepository(get(), get(), get(), get()) }
    single<InternalLinkRepository> { InternalLinkRepositoryImpl(get(), get(), get()) }

    // ─── ViewModels ─────────────────────────────────────────────────────

    viewModel { NotesListViewModel(repo = get(), idGen = get()) }

    // NoteEditor: ai is optional — improveNote is null when AI is not configured
    // (the AI button will be hidden in UI when NoteAiController.isAvailable == false).
    viewModel {
        NoteEditor(
            repo = get(),
            linkRepo = get(),
            idGen = get(),
            ai = NoteAiController(
                improveNote = getOrNull<ImproveNoteUseCase>()?.let(::improveNoteLambda),
            ),
            log = get<Logger>(),
            // scope omitted — default AutoCloseableCoroutineScope() applies
        )
    }

    viewModel {
        NotePreview(
            repo = get(),
            linkRepo = get(),
        )
    }
}
