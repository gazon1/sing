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
 * @param targetKind The entity kind each item in this proposal applies to.
 * @param targetId The entity id each item applies to.
 * @param userId Owner.
 * @param source Which surface produced the proposal.
 * @param status Aggregate status, derived from the items.
 * @param createdAt Creation timestamp.
 * @param updatedAt Last modification timestamp.
 * @param items The individual proposed changes.
 */
data class AiProposal(
    val id: ProposalId,
    val targetKind: String,
    val targetId: String,
    val userId: UserId,
    val source: ProposalSource,
    val status: ProposalStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val items: List<ProposalItem> = emptyList(),
) {
    /** Items still awaiting a decision. */
    val pendingItems: List<ProposalItem> get() = items.filter { it.status.isPending }

    companion object {
        const val TARGET_KIND_TASK = "TASK"
        const val TARGET_KIND_NOTE = "NOTE"
        const val TARGET_KIND_PROJECT = "PROJECT"
        const val TARGET_KIND_TAG = "TAG"
    }
}

/** True while the item has not been decided. */
val ProposalItemStatus.isPending: Boolean get() = this == ProposalItemStatus.Pending
