package com.singularity.todo.feature.proposals.presentation

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
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the screen shows: the proposals waiting for a decision. */
data class ProposalsUiState(val isLoading: Boolean = true, val proposals: List<AiProposal> = emptyList())

/** What the user did on the proposals screen. */
sealed interface ProposalsIntent : MviIntent {
    /** Apply one change. */
    data class Confirm(val itemId: ProposalItemId) : ProposalsIntent

    /** Turn one change down, with the reason the user typed — if they typed one. */
    data class Reject(val itemId: ProposalItemId, val reason: String? = null) : ProposalsIntent

    /** Apply every change of one proposal. */
    data class ConfirmAll(val proposalId: ProposalId) : ProposalsIntent

    /** Turn every change of one proposal down. */
    data class Dismiss(val proposalId: ProposalId) : ProposalsIntent
}

/** What the proposals screen tells the user, once. */
sealed interface ProposalsUiEvent : MviEvent {
    data class ShowError(val message: String) : ProposalsUiEvent
}

/**
 * The screen of the proposals waiting for a decision.
 *
 * ## Why this screen exists
 *
 * A proposal is a change nobody has agreed to yet, and until now there was nowhere to
 * agree to one. The repository could list the pending proposals — the method was
 * there, with no production caller — and the delete tools' proposals were waiting
 * somewhere the user could not reach.
 *
 * ## What the screen shows
 *
 * The proposals themselves, not a projection of them. The card that draws a proposal
 * is the same card the task detail and the note editor draw, so a proposal looks the
 * same wherever the user meets it — and the user learns one set of buttons instead
 * of three.
 *
 * ## What it does not do
 *
 * It does not apply anything the user did not ask it to, and it does not decide. The
 * model of a change is the model's; the user reads the summary and says yes or no.
 */
class ProposalsViewModel(
    private val proposals: ProposalRepository,
    private val applyProposalItem: ApplyProposalItemUseCase,
    private val currentUser: ProfileAwareCurrentUser,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    private val scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<ProposalsUiState, ProposalsIntent, ProposalsUiEvent>(
        initialState = ProposalsUiState(),
        crashReporter = crashReporter,
        scope = scope,
    ) {

    /**
     * The collector of what is waiting.
     *
     * A property initializer rather than a `launch` in an `init` block: the
     * initializer runs after the constructor's fields are assigned, so the collector
     * can never observe a half-built ViewModel — the invariant
     * `NoCoroutineLaunchInInit` exists to protect.
     */
    private val pendingWatch = vmScope.launch {
        proposals.watchProposalsByStatus(ProposalStatus.Pending)
            .map { list -> list.filter { proposal -> proposal.items.any { it.status == ProposalItemStatus.Pending } } }
            .collect { list ->
                updateState { ProposalsUiState(isLoading = false, proposals = list) }
            }
    }

    /** The collector stops with the ViewModel. */
    override fun onCleared() {
        pendingWatch.cancel()
        super.onCleared()
    }

    override fun onIntent(intent: ProposalsIntent) {
        when (intent) {
            is ProposalsIntent.Confirm -> decide(intent.itemId, confirm = true)
            is ProposalsIntent.Reject -> reject(intent.itemId, intent.reason)
            is ProposalsIntent.ConfirmAll -> confirmAll(intent.proposalId)
            is ProposalsIntent.Dismiss -> dismiss(intent.proposalId)
        }
    }

    private fun decide(itemId: ProposalItemId, confirm: Boolean) {
        val userId = currentUser.scopedUserId.value
        val label = if (confirm) "Apply the change" else "Reject the change"
        emitError(label, ProposalsUiEvent::ShowError) {
            if (confirm) {
                applyProposalItem.confirm(itemId, userId)
            } else {
                applyProposalItem.reject(itemId, userId)
            }
        }
    }

    /** A rejection the user gave a reason for — the reason is what the model learns from. */
    private fun reject(itemId: ProposalItemId, reason: String?) {
        val userId = currentUser.scopedUserId.value
        emitError("Reject the change", ProposalsUiEvent::ShowError) {
            applyProposalItem.reject(itemId, userId, reason)
        }
    }

    private fun confirmAll(proposalId: ProposalId) {
        val userId = currentUser.scopedUserId.value
        emitError("Apply the changes", ProposalsUiEvent::ShowError) {
            runCatchingCancellable { applyProposalItem.confirmAll(proposalId, userId) }
        }
    }

    /**
     * Turns every change of one proposal down.
     *
     * "Dismiss" is the whole proposal: the user does not want this change, and
     * answering item by item would ask them the same question about each part of
     * one decision.
     */
    private fun dismiss(proposalId: ProposalId) {
        val userId = currentUser.scopedUserId.value
        emitError("Dismiss the proposal", ProposalsUiEvent::ShowError) {
            runCatchingCancellable {
                val waiting = proposals.watchProposal(proposalId).first()?.items.orEmpty()
                    .filter { it.status == ProposalItemStatus.Pending }
                waiting.forEach { item -> applyProposalItem.reject(item.id, userId) }
            }
        }
    }
}
