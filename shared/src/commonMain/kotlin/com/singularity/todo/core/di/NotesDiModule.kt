package com.singularity.todo.core.di

import co.touchlab.kermit.Logger
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.feature.ai.use_cases.ExtractActionsUseCase
import com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase
import com.singularity.todo.feature.ai.use_cases.RewriteNoteUseCase
import com.singularity.todo.feature.ai.use_cases.SuggestTagsUseCase
import com.singularity.todo.feature.ai.use_cases.SummarizeNoteUseCase
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
import org.koin.core.scope.Scope
import org.koin.dsl.module
import kotlin.time.Clock

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
            noteDao = get(),
            textIndex = get(),
            clock = get(),
            currentUser = get(),
            syncRepository = get(),
            unitOfWork = get(),
        )
    }
    single<InternalLinkRepository> {
        InternalLinkRepositoryImpl(
            noteDao = get(),
            taskDao = get(),
            textIndex = get(),
            currentUser = get(),
        )
    }

    // ─── ViewModels ─────────────────────────────────────────────────────

    viewModel { NotesListViewModel(repo = get(), crashReporter = get()) }

    // NoteEditor: ai is optional — improveNote is null when AI is not configured
    // (the AI button will be hidden in UI when NoteAiController.isAvailable == false).
    // proposals + applyProposal wire the note-AI-through-proposals flow (MR-A3).
    //
    // `getOptionalOrNull`, not `getOrNull`: an AI binding that *exists* but cannot
    // be created is a broken binding, not a missing feature, and it used to take
    // the whole editor down in composition instead of degrading to "AI is not
    // configured". The failure is logged, so a broken binding is still visible.
    viewModel {
        NoteEditor(
            repo = get(),
            linkRepo = get(),
            idGen = get(),
            ai = NoteAiController(
                improveNote = getOptionalOrNull<ImproveNoteUseCase> { reportBrokenAi(it) }
                    ?.let(::improveNoteLambda),
                summarizeNote = getOptionalOrNull<SummarizeNoteUseCase> { reportBrokenAi(it) }
                    ?.let(::summarizeNoteLambda),
                extractActions = getOptionalOrNull<ExtractActionsUseCase> { reportBrokenAi(it) }
                    ?.let(::extractActionsLambda),
                rewriteNote = getOptionalOrNull<RewriteNoteUseCase> { reportBrokenAi(it) }
                    ?.let(::rewriteNoteLambda),
                suggestTags = getOptionalOrNull<SuggestTagsUseCase> { reportBrokenAi(it) }
                    ?.let(::suggestTagsLambda),
            ),
            proposals = get<ProposalRepository>(),
            applyProposal = get<ApplyProposalItemUseCase>(),
            log = get<Logger>(),
            currentUser = get<ProfileAwareCurrentUser>(),
            clock = get<Clock>(),
            // scope omitted — the default derives its failure policy from crashReporter
            crashReporter = get<CrashReportingPort>(),
        )
    }

    viewModel {
        NotePreview(
            repo = get(),
            linkRepo = get(),
            crashReporter = get(),
        )
    }
}

/**
 * Reports an AI binding that exists but cannot be created.
 *
 * The note editor treats the AI as optional: a missing binding hides the AI
 * button, and a *broken* one must do the same rather than crash the editor. It
 * must not do the same *silently* — a broken binding is a defect, and this is
 * where it leaves a trace.
 */
private fun Scope.reportBrokenAi(failure: Throwable) {
    getOrNull<Logger>()?.w(
        "An AI binding exists but cannot be created — the note editor continues without AI",
        failure,
    )
}
