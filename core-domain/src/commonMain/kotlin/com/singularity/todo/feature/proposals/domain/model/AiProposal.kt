package com.singularity.todo.feature.proposals.domain.model

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.UserId
import kotlin.time.Instant

/**
 * Where a proposal came from.
 *
 * Recorded for the feedback loop: a model that keeps proposing the same rejected
 * change on one surface is a different problem from one that does it everywhere.
 */
enum class ProposalSource {
    /** The [com.singularity.todo.feature.proposals] card on a list row. */
    Card,

    /** The task hub's AI section. */
    Detail,

    /** Actions extracted from a note. */
    ExtractActions,

    /** A background agent run. */
    Agent,
}

/**
 * Aggregate status of a proposal, derived from its items' statuses.
 *
 * @see ProposalStatusReducer for the derivation rule.
 */
enum class ProposalStatus {
    /** At least one item is still awaiting a decision. */
    Pending,

    /** Every item is decided, at least one confirmed and at least one rejected. */
    PartiallyResolved,

    /** Every item is confirmed. */
    Resolved,

    /** Withdrawn — the model or the user took it back. Nothing is applicable. */
    Retracted,
}

/**
 * A batch of proposed changes awaiting confirmation on one entity.
 *
 * Proposals exist so the AI never writes to a task or note directly: it produces this,
 * the user confirms or rejects each [ProposalItem], and only then does anything change.
 *
 * Proposals may target a task, note, project, or tag identified by `targetKind` / `targetId`.
 *
 * @param id Unique identity.
 * @param targetKind The entity kind each item in this proposal applies to. It is
 *   derived from the changes themselves — see [ProposalTarget.targetKind] — so a
 *   proposal cannot be addressed to the wrong thing.
 * @param targetId The entity id each item applies to.
 * @param userId Owner.
 * @param source Which surface produced the proposal.
 * @param createdAt Creation timestamp.
 * @param updatedAt Last modification timestamp.
 * @param items The individual proposed changes.
 */
data class AiProposal(
    val id: ProposalId,
    val targetKind: ProposalTargetKind,
    val targetId: String,
    val userId: UserId,
    val source: ProposalSource,
    val createdAt: Instant,
    val updatedAt: Instant,
    val items: List<ProposalItem> = emptyList(),
) {
    /**
     * The aggregate status, derived from the changes.
     *
     * It used to be a column of its own, read from the row and written beside the
     * changes. Two copies of one fact, and they could disagree: a proposal whose
     * items were all decided still said "waiting", because the summary row was
     * written before the decisions and never recomputed. So the status is what the
     * changes say it is, and the row's column is a cache of this answer.
     */
    val status: ProposalStatus get() = ProposalStatusReducer.reduce(items.map { it.status })

    /**
     * Changes that still wait for the user's decision.
     *
     * A change whose status this build cannot read is in here: the user never
     * said yes and never said no, so it is still theirs to turn down. It is not
     * applicable, and the card says why.
     */
    val pendingItems: List<ProposalItem> get() = items.filter { !it.status.isDecided }

    /**
     * Changes that have already been decided.
     *
     * The card draws them, muted and with the reason. A refusal the user cannot see
     * is a refusal they cannot learn from — and a proposal that is half decided still
     * has to tell the whole story.
     */
    val decidedItems: List<ProposalItem> get() = items.filter { it.status.isDecided }

    /**
     * True while something in this proposal still waits for a decision.
     *
     * The question a surface asks: "is there anything here for the user?" The
     * answer belongs to the proposal, so a surface does not derive it from a status
     * of its own.
     */
    val isWaiting: Boolean get() = pendingItems.isNotEmpty()
}
