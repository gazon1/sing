package com.singularity.todo.feature.proposals.domain.logic

import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus

/**
 * Derives a proposal's aggregate status from its items' statuses.
 *
 * Pure and total: it is a fold over a list, with a defined answer for the empty list,
 * so it can be exhaustively unit-tested with no database and no fakes.
 *
 * The rule is deliberately conservative — anything that is not fully resolved stays
 * [ProposalStatus.Pending]. A proposal card that still offers "confirm all" is
 * harmless; one that disappears while an item is still awaiting a decision loses the
 * user's work silently.
 */
object ProposalStatusReducer {

    /**
     * @param itemStatuses Current status of every item in the proposal.
     * @return The aggregate status.
     */
    fun reduce(itemStatuses: List<ProposalItemStatus>): ProposalStatus = when {
        // An empty proposal has nothing to resolve. Resolved is the honest answer:
        // there is no outstanding decision for the card to prompt about.
        itemStatuses.isEmpty() -> ProposalStatus.Resolved

        itemStatuses.any { it == ProposalItemStatus.Pending } -> ProposalStatus.Pending

        // A retraction anywhere retires the whole proposal — the model took it back.
        itemStatuses.any { it == ProposalItemStatus.Retracted } -> ProposalStatus.Retracted

        itemStatuses.all { it == ProposalItemStatus.Confirmed } -> ProposalStatus.Resolved

        // Everything left is a mix of confirmed and rejected.
        else -> ProposalStatus.PartiallyResolved
    }
}
