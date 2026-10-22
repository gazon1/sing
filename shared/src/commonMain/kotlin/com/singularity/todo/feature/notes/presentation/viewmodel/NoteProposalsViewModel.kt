package com.singularity.todo.feature.notes.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.runCatchingCancellable
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
import kotlinx.coroutines.launch

/** The changes waiting for a decision on this note. */
data class NoteProposalsUiState(val proposals: List<AiProposal> = emptyList())

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
    private val currentUser: ProfileAwareCurrentUser,
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<NoteProposalsUiState, NoteProposalsIntent, NoteProposalsUiEvent>(
        initialState = NoteProposalsUiState(),
        crashReporter = crashReporter,
        scope = scope,
    ) {

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
                updateState { NoteProposalsUiState(proposals = list.filter { it.pendingItems.isNotEmpty() }) }
            }
    }

    /** The collector stops with the ViewModel. */
    override fun onCleared() {
        pendingWatch.cancel()
        super.onCleared()
    }

    override fun onIntent(intent: NoteProposalsIntent) {
        when (intent) {
            is NoteProposalsIntent.Confirm -> decide(intent.itemId, confirm = true)
            is NoteProposalsIntent.Reject -> decide(intent.itemId, confirm = false)
            is NoteProposalsIntent.ConfirmAll -> confirmAll(intent.proposalId)
            is NoteProposalsIntent.Dismiss -> dismiss(intent.proposalId)
        }
    }

    /** Apply every change of the proposal at once. */
    private fun confirmAll(proposalId: ProposalId) {
        val userId = currentUser.scopedUserId.value
        emitError("Apply the changes", NoteProposalsUiEvent::ShowError) {
            runCatchingCancellable { applyProposalItem.confirmAll(proposalId, userId) }
        }
    }

    /** Turn every change of the proposal down, one by one. */
    private fun dismiss(proposalId: ProposalId) {
        val userId = currentUser.scopedUserId.value
        val waiting = stateFlow.value.proposals
            .firstOrNull { it.id == proposalId }
            ?.pendingItems
            .orEmpty()
        emitError("Turn the changes down", NoteProposalsUiEvent::ShowError) {
            runCatchingCancellable {
                waiting.forEach { item -> applyProposalItem.reject(item.id, userId, null) }
            }
        }
    }

    private fun decide(itemId: ProposalItemId, confirm: Boolean) {
        val userId = currentUser.scopedUserId.value
        val label = if (confirm) "Apply the change" else "Reject the change"
        emitError(label, NoteProposalsUiEvent::ShowError) {
            if (confirm) {
                applyProposalItem.confirm(itemId, userId)
            } else {
                applyProposalItem.reject(itemId, userId)
            }
        }
    }
}
