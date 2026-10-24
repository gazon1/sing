package com.singularity.todo.feature.notes.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.ui.MviEvent
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.EditProposalItemUseCase
import com.singularity.todo.feature.proposals.presentation.ProposalDecisionStyles
import com.singularity.todo.feature.proposals.presentation.ProposalDecisions
import com.singularity.todo.feature.proposals.presentation.ProposalErrorSink
import kotlinx.coroutines.launch

/**
 * The changes waiting for a decision on this note.
 *
 * @property failures Why the last attempt to apply or to change a change failed, by
 *   the change it belongs to. The reason sits next to the change rather than in one
 *   message about a batch: a user who cannot see *which* change failed cannot act on
 *   it.
 */
data class NoteProposalsUiState(
    val proposals: List<AiProposal> = emptyList(),
    val failures: Map<ProposalItemId, String> = emptyMap(),
)

/** What the user did about a proposed change. */
sealed interface NoteProposalsIntent : MviIntent {
    /** Apply one change. */
    data class Confirm(val itemId: ProposalItemId) : NoteProposalsIntent

    /** Turn one change down. */
    data class Reject(val itemId: ProposalItemId, val reason: String?) : NoteProposalsIntent

    /** Apply every change of one proposal. */
    data class ConfirmAll(val proposalId: ProposalId) : NoteProposalsIntent

    /** Turn every change of one proposal down. */
    data class Dismiss(val proposalId: ProposalId) : NoteProposalsIntent

    /** Change the value of one change, before it is applied. */
    data class Edit(val itemId: ProposalItemId, val text: String) : NoteProposalsIntent
}

/** What the note editor tells the user, once. */
sealed interface NoteProposalsUiEvent : MviEvent {
    data class ShowError(val message: String) : NoteProposalsUiEvent
}

/**
 * The AI's proposed changes to one note, and the user's answer to them.
 *
 * ## Why this exists
 *
 * The note editor's AI actions do not change the note. They save a proposal — a
 * proposed change, with a summary the user can read — and wait. That was the whole
 * design, and it worked for one half: the proposal was saved.
 *
 * The other half was missing. Nothing watched for those proposals, so the user was
 * asked for a rewrite, the rewrite was written, and nothing appeared. The comment in
 * the editor said "the UI watches proposals"; no UI did. A proposal nobody can reach
 * is a proposal the user can only answer by telling the assistant to stop.
 *
 * So this watches the note's proposals and offers the answer, which is a word about
 * one specific change: yes or no.
 */
class NoteProposalsViewModel(
    private val noteId: NoteId,
    private val proposals: ProposalRepository,
    private val applyProposalItem: ApplyProposalItemUseCase,
    private val editProposalItem: EditProposalItemUseCase,
    private val currentUser: ProfileAwareCurrentUser,
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<NoteProposalsUiState, NoteProposalsIntent, NoteProposalsUiEvent>(
        initialState = NoteProposalsUiState(),
        crashReporter = crashReporter,
        scope = scope,
    ) {

    /**
     * The five operations, in one place.
     *
     * The note editor, the review screen and the task detail all answer a
     * proposal, and they answer it the same way — so the operations live in
     * [ProposalDecisions] and this editor brings its own wording, its own
     * failure store and its own error sink.
     */
    private val decisions = ProposalDecisions(
        applyProposalItem = applyProposalItem,
        editProposalItem = editProposalItem,
        style = ProposalDecisionStyles.noteEditor,
        errors = ProposalErrorSink { label, _, error ->
            // The ViewModel's own error path: it reports the failure to the crash
            // reporter and shows it as a one-shot event. The failure is rethrown
            // into that path because the path is what reports it.
            emitError(label, NoteProposalsUiEvent::ShowError) { throw error }
        },
        userId = { currentUser.scopedUserId.value },
    )

    /**
     * The reasons, mirrored into the state the editor draws from.
     *
     * The decisions keep the reasons — one store, whatever the surface — and this
     * editor shows them. A reason kept inside the editor's own state alone was
     * lost on every rebuild.
     */
    private val failureWatch = vmScope.launch {
        decisions.failures.collect { reasons ->
            updateState { state -> state.copy(failures = reasons) }
        }
    }

    /**
     * The collector of what is waiting.
     *
     * A property initializer rather than a `launch` in an `init` block: the
     * initializer runs after the constructor's fields are assigned, so the collector
     * can never observe a half-built ViewModel.
     */
    private val pendingWatch = vmScope.launch {
        proposals.watchProposalsForNote(noteId)
            .collect { list ->
                // The failures are kept: a reason the user has not read yet is still a
                // reason, and a new list of proposals must not wipe it.
                updateState { state ->
                    state.copy(proposals = list.filter { it.isWaiting })
                }
            }
    }

    /** The collector stops with the ViewModel. */
    override fun onCleared() {
        pendingWatch.cancel()
        failureWatch.cancel()
        super.onCleared()
    }

    override fun onIntent(intent: NoteProposalsIntent) {
        when (intent) {
            is NoteProposalsIntent.Confirm -> vmScope.launch { decisions.applyChange(intent.itemId) }

            is NoteProposalsIntent.Reject -> vmScope.launch { decisions.turnDownChange(intent.itemId, intent.reason) }

            is NoteProposalsIntent.ConfirmAll -> vmScope.launch { decisions.applyAll(intent.proposalId) }

            is NoteProposalsIntent.Dismiss -> {
                // The waiting changes are read when the intent arrives, the way they
                // always were: the note editor already holds them.
                val waiting = stateFlow.value.proposals
                    .firstOrNull { it.id == intent.proposalId }
                    ?.pendingItems
                    .orEmpty()
                vmScope.launch { decisions.dismiss { waiting } }
            }

            is NoteProposalsIntent.Edit -> vmScope.launch { decisions.changeValue(intent.itemId, intent.text) }
        }
    }
}
