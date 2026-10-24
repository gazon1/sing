package com.singularity.todo.feature.proposals.presentation

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
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
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.EditProposalItemUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What the screen shows: the proposals waiting for a decision.
 *
 * @property failures Why the last attempt to apply or to change an item failed, by
 *   item. The card shows the reason under the item it belongs to, so the reason is
 *   where the user's attention already is. A reason that only ever appears in a
 *   message that is gone by the time they look again is not a visible reason.
 */
data class ProposalsUiState(
    val isLoading: Boolean = true,
    val proposals: List<AiProposal> = emptyList(),
    val failures: Map<ProposalItemId, String> = emptyMap(),
)

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

    /** Change the value of one change, before it is applied. */
    data class Edit(val itemId: ProposalItemId, val text: String) : ProposalsIntent
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
    private val editProposalItem: EditProposalItemUseCase,
    private val currentUser: ProfileAwareCurrentUser,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    private val scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<ProposalsUiState, ProposalsIntent, ProposalsUiEvent>(
        initialState = ProposalsUiState(),
        crashReporter = crashReporter,
        scope = scope,
    ) {

    /**
     * The five operations, in one place.
     *
     * The review screen, the note editor and the task detail all answer a
     * proposal, and they answer it the same way — so the operations live in
     * [ProposalDecisions] and this screen brings its own wording, its own
     * failure store and its own error sink.
     */
    private val decisions = ProposalDecisions(
        applyProposalItem = applyProposalItem,
        editProposalItem = editProposalItem,
        style = ProposalDecisionStyles.reviewScreen,
        errors = ProposalErrorSink { label, _, error ->
            // The ViewModel's own error path: it reports the failure to the crash
            // reporter and shows it as a one-shot event. The failure is rethrown
            // into that path because the path is what reports it.
            emitError(label, ProposalsUiEvent::ShowError) { throw error }
        },
        userId = { currentUser.scopedUserId.value },
    )

    /**
     * The reasons, mirrored into the state the screen draws from.
     *
     * The decisions keep the reasons — one store, whatever the surface — and this
     * screen shows them. A reason kept inside the screen's own state alone was
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
     * can never observe a half-built ViewModel — the invariant
     * `NoCoroutineLaunchInInit` exists to protect.
     */
    private val pendingWatch = vmScope.launch {
        // The domain answers "is anything still waiting": a surface that asks
        // the status itself has opinions about the lifecycle, and the
        // lifecycle is not the surface's.
        proposals.watchWaitingProposals()
            .collect { list ->
                updateState { state ->
                    state.copy(isLoading = false, proposals = list)
                }
            }
    }

    /** The collector stops with the ViewModel. */
    override fun onCleared() {
        pendingWatch.cancel()
        failureWatch.cancel()
        super.onCleared()
    }

    override fun onIntent(intent: ProposalsIntent) {
        when (intent) {
            is ProposalsIntent.Confirm -> vmScope.launch { decisions.applyChange(intent.itemId) }

            is ProposalsIntent.Reject -> vmScope.launch { decisions.turnDownChange(intent.itemId, intent.reason) }

            is ProposalsIntent.ConfirmAll -> vmScope.launch { decisions.applyAll(intent.proposalId) }

            is ProposalsIntent.Dismiss -> vmScope.launch {
                decisions.dismiss { waiting(intent.proposalId) }
            }

            is ProposalsIntent.Edit -> vmScope.launch { decisions.changeValue(intent.itemId, intent.text) }
        }
    }

    /**
     * The changes of a proposal that are still waiting.
     *
     * Read from the repository and not from the state: the state holds the
     * proposals the screen was last given, and a proposal the user is looking at
     * may have changed since.
     */
    private suspend fun waiting(proposalId: ProposalId): List<ProposalItem> =
        proposals.watchProposal(proposalId).first()?.pendingItems.orEmpty()
}
