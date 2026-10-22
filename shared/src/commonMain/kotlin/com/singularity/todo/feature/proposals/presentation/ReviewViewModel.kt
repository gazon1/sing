package com.singularity.todo.feature.proposals.presentation

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One change waiting for a decision. */
data class ReviewItem(val id: String, val summary: String)

/** One proposal, with the changes it holds. */
data class ReviewProposal(val id: String, val targetKind: String, val targetId: String, val items: List<ReviewItem>)

/** What the review screen shows. */
data class ReviewUiState(val isLoading: Boolean = true, val proposals: List<ReviewProposal> = emptyList())

/** What the user did on the review screen. */
sealed interface ReviewIntent : MviIntent {
    /** Apply one change. */
    data class Confirm(val itemId: ProposalItemId) : ReviewIntent

    /** Turn one change down. */
    data class Reject(val itemId: ProposalItemId) : ReviewIntent
}

/** What the review screen tells the user, once. */
sealed interface ReviewUiEvent : MviEvent {
    data class ShowError(val message: String) : ReviewUiEvent
}

/**
 * The review screen's ViewModel: the proposals waiting for a decision.
 *
 * ## Why this screen exists
 *
 * A proposal is a change nobody has agreed to yet, and until now there was nowhere to
 * agree to one. The repository could list the pending proposals — the method was
 * there, with no production caller — and the delete tools' proposals were waiting
 * somewhere the user could not reach.
 *
 * So the screen is a list of what is waiting, and two buttons per change. The apply
 * path is the app's own: [ApplyProposalItemUseCase], which validates before it claims
 * and compares-and-sets so a double-tap cannot apply twice.
 *
 * ## What it does not do
 *
 * It does not apply anything the user did not ask it to, and it does not decide. The
 * model of a change is the model's; the user reads the summary and says yes or no.
 */
class ReviewViewModel(
    private val proposals: ProposalRepository,
    private val applyProposalItem: ApplyProposalItemUseCase,
    private val currentUser: ProfileAwareCurrentUser,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    private val scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<ReviewUiState, ReviewIntent, ReviewUiEvent>(
        initialState = ReviewUiState(),
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
            .map { list -> list.map(::toReview).filter { it.items.isNotEmpty() } }
            .collect { list ->
                updateState { ReviewUiState(isLoading = false, proposals = list) }
            }
    }

    /** The collector stops with the ViewModel. */
    override fun onCleared() {
        pendingWatch.cancel()
        super.onCleared()
    }

    override fun onIntent(intent: ReviewIntent) {
        when (intent) {
            is ReviewIntent.Confirm -> decide(intent.itemId, confirm = true)
            is ReviewIntent.Reject -> decide(intent.itemId, confirm = false)
        }
    }

    /** The proposal as the screen shows it: only the changes still waiting. */
    private fun toReview(proposal: AiProposal): ReviewProposal =
        ReviewProposal(
            id = proposal.id.value,
            targetKind = proposal.targetKind,
            targetId = proposal.targetId,
            items = proposal.items
                .filter { it.status == ProposalItemStatus.Pending }
                .map { ReviewItem(id = it.id.value, summary = it.humanSummary) },
        )

    private fun decide(itemId: ProposalItemId, confirm: Boolean) {
        val userId = currentUser.scopedUserId.value
        val label = if (confirm) "Apply the change" else "Reject the change"
        emitError(label, ReviewUiEvent::ShowError) {
            if (confirm) {
                applyProposalItem.confirm(itemId, userId)
            } else {
                applyProposalItem.reject(itemId, userId)
            }
        }
    }
}
