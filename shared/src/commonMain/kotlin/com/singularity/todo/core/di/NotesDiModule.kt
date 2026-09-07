package com.singularity.todo.core.di

import com.singularity.todo.feature.notes.NotesRepository
import com.singularity.todo.feature.notes.RoomNotesRepository
import com.singularity.todo.feature.notes.CreateNoteUseCase
import com.singularity.todo.feature.notes.UpdateNoteUseCase
import com.singularity.todo.feature.notes.NotesViewModel
import com.singularity.todo.feature.notes.RichEditorMarkdownHtmlPort
import com.singularity.todo.feature.notes.MarkdownHtmlPort
import com.singularity.todo.feature.search.InternalLinkRepository
import com.singularity.todo.feature.search.InternalLinkRepositoryImpl
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Notes feature DI: repositories, use cases, ViewModels, ports.
 */
fun notesModule(): org.koin.core.module.Module = module {
    // ─── Repository ─────────────────────────────────────────────────────

    single<NotesRepository> { RoomNotesRepository(get(), get()) }
    single<InternalLinkRepository> { InternalLinkRepositoryImpl(get(), get()) }

    // ─── Use Cases ──────────────────────────────────────────────────────

    factory { CreateNoteUseCase(get(), get()) }
    factory { UpdateNoteUseCase(get(), get()) }

    // ─── Ports ──────────────────────────────────────────────────────────

    single<MarkdownHtmlPort> { RichEditorMarkdownHtmlPort() }

    // ─── ViewModels ─────────────────────────────────────────────────────

    // Explicit viewModel { } required because NotesViewModel has optional
    // nullable deps (improveNote, logger) resolved via getOrNull().
    viewModel {
        NotesViewModel(
            repo = get(),
            htmlPort = get(),
            currentUser = get(),
            idGen = get(),
            autosaveScheduler = get(),
            improveNote = getOrNull(),
            logger = null,  // Logger created directly in constructor via Logger.withTag()
            scopeOverride = null,
        )
    }
}
