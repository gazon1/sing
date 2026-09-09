package com.singularity.todo.core.di

import com.singularity.todo.feature.notes.NoteEditor
import com.singularity.todo.feature.notes.NotePreview
import com.singularity.todo.feature.notes.NotesListViewModel
import com.singularity.todo.feature.notes.NotesRepository
import com.singularity.todo.feature.notes.RoomNotesRepository
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

    single<NotesRepository> { RoomNotesRepository(get(), get()) }
    single<InternalLinkRepository> { InternalLinkRepositoryImpl(get(), get()) }

    // ─── ViewModels ─────────────────────────────────────────────────────

    viewModel { NotesListViewModel(get(), get()) }

    // NoteEditor: improveNote is optional — use getOrNull() so Koin can
    // instantiate without it (the AI button will be hidden in UI when null).
    viewModel {
        NoteEditor(
            repo = get(),
            currentUser = get(),
            idGen = get(),
            autosaveScheduler = get(),
            improveNote = getOrNull(),
        )
    }

    viewModel {
        NotePreview(
            repo = get(),
            linkRepo = get(),
            currentUser = get(),
        )
    }
}
