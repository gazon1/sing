package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase
import com.singularity.todo.feature.notes.data.NotesRepositoryImpl
import com.singularity.todo.feature.notes.domain.editor.NoteAiController
import com.singularity.todo.feature.notes.domain.editor.extractActionsLambda
import com.singularity.todo.feature.notes.domain.editor.improveNoteLambda
import com.singularity.todo.feature.notes.domain.editor.rewriteNoteLambda
import com.singularity.todo.feature.notes.domain.editor.suggestTagsLambda
import com.singularity.todo.feature.notes.domain.editor.summarizeNoteLambda
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.notes.presentation.viewmodel.NoteEditor
import com.singularity.todo.feature.notes.presentation.viewmodel.NotePreview
import com.singularity.todo.feature.notes.presentation.viewmodel.NotesListViewModel
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import com.singularity.todo.feature.search.data.InternalLinkRepositoryImpl
import com.singularity.todo.feature.search.domain.port.InternalLinkRepository
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

    single<NotesRepository> {
        NotesRepositoryImpl(
            get(),
            get(),
            get(),
            get(),
        )
    }
    single<InternalLinkRepository> {
        InternalLinkRepositoryImpl(
            get(),
            get(),
            get(),
        )
    }

    // ─── ViewModels ─────────────────────────────────────────────────────

    viewModel { NotesListViewModel(repo = get()) }

    // NoteEditor: ai is optional — improveNote is null when AI is not configured
    // (the AI button will be hidden in UI when NoteAiController.isAvailable == false).
    // proposals + applyProposal wire the note-AI-through-proposals flow (MR-A3).
    viewModel {
        NoteEditor(
            repo = get(),
            linkRepo = get(),
            idGen = get(),
            ai = NoteAiController(
                improveNote = getOrNull<ImproveNoteUseCase>()?.let(::improveNoteLambda),
                summarizeNote = getOrNull<com.singularity.todo.feature.ai.use_cases.SummarizeNoteUseCase>()?.let(
                    ::summarizeNoteLambda,
                ),
                extractActions = getOrNull<com.singularity.todo.feature.ai.use_cases.ExtractActionsUseCase>()?.let(
                    ::extractActionsLambda,
                ),
                rewriteNote = getOrNull<com.singularity.todo.feature.ai.use_cases.RewriteNoteUseCase>()?.let(
                    ::rewriteNoteLambda,
                ),
                suggestTags = getOrNull<com.singularity.todo.feature.ai.use_cases.SuggestTagsUseCase>()?.let(
                    ::suggestTagsLambda,
                ),
            ),
            proposals = get<ProposalRepository>(),
            applyProposal = get<ApplyProposalItemUseCase>(),
            log = get<Logger>(),
            currentUser = get<ProfileAwareCurrentUser>(),
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
