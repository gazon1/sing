package com.singularity.todo.feature.proposals.domain.model

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import kotlin.time.Instant

/**
 * Lifecycle of a single [ProposalItem].
 *
 * The transition out of [Pending] is a compare-and-set at the SQL level
 * (`UPDATE ... WHERE status = 'Pending'`), so exactly one of confirm/reject wins
 * even under a double-tap.
 */
enum class ProposalItemStatus {
    /** Awaiting the user's decision. */
    Pending,

    /** Accepted — the change has been applied to the task. */
    Confirmed,

    /** Declined — nothing was applied. [ProposalItem.rejectionReason] may explain why. */
    Rejected,

    /** Withdrawn along with the owning proposal. */
    Retracted,
}

/** Who made the decision. */
enum class DecidedActor {
    /** The human owner of the task. */
    User,

    /** Applied on the user's behalf by a batch action. */
    Ai,
}

/**
 * One proposed change, individually confirmable.
 *
 * A proposal that changes five things is stored as five items, not one: a single
 * "apply all" tap would then be five separate decisions, each with its own
 * rejection reason, and the user can keep two of the five.
 *
 * @param id Unique identity.
 * @param proposalId Owning proposal.
 * @param kind What change is being asked for.
 * @param targetId What the change applies to. For [ProposalItemKind.SetTaskField] this
 *   is the task id; for tag variants it is the tag; for collection variants it is the
 *   owning task. Kept separate from [kind] so the fingerprint can distinguish two
 *   items of the same kind targeting different objects.
 * @param humanSummary One-line description shown in the proposal card. The model
 *   writes this; the user confirms against it, so it must be plain language, not a
 *   serialized payload.
 * @param status Current lifecycle state.
 * @param fingerprint Stable identity of the change, used to block re-proposals of
 *   something already rejected. See
 *   [com.singularity.todo.feature.proposals.domain.logic.ProposalFingerprint].
 * @param sortOrder Display order within the card.
 * @param decidedAt When the decision was made, null while [status] is [ProposalItemStatus.Pending].
 * @param decidedActor Who decided.
 * @param rejectionReason Why it was rejected. Free text; only quoted back to the
 *   model when the user supplies one.
 */
data class ProposalItem(
    val id: ProposalItemId,
    val proposalId: ProposalId,
    val kind: ProposalItemKind,
    val targetId: String,
    val humanSummary: String,
    val status: ProposalItemStatus,
    val fingerprint: String,
    val sortOrder: Int = 0,
    val decidedAt: Instant? = null,
    val decidedActor: DecidedActor? = null,
    val rejectionReason: String? = null,
) {
    /** True when the user gave a reason worth feeding back to the model. */
    val hasRejectionFeedback: Boolean get() = !rejectionReason.isNullOrBlank()

    /**
     * Why this change cannot be read, when its stored value no longer reads.
     *
     * The value was written by a build that knew the field, and this one does
     * not. The change is still on the card — with this reason next to it —
     * because a change the user cannot see is a change nobody decided about.
     */
    val readFailure: String? get() = kind.readFailure

    /**
     * What the user reads for a change that has already been decided.
     *
     * The wording belongs to the change, not to the surface: three surfaces draw
     * the same card, and a card that says "Turned down" in one place and
     * "Rejected" in another is two answers to one question. So the surface
     * prints what the domain says, and never asks what the status is.
     */
    val outcomeLabel: String
        get() = when (status) {
            ProposalItemStatus.Confirmed -> "Applied"
            ProposalItemStatus.Rejected -> "Turned down"
            ProposalItemStatus.Retracted -> "Withdrawn"
            ProposalItemStatus.Pending -> "Waiting"
        }

    companion object {

        /**
         * A change waiting for a decision.
         *
         * The surfaces that start a proposal build the change they propose, and a
         * change that was proposed is waiting — that is what "proposed" means. So
         * the status is the domain's word for it, and a surface does not name it:
         * a surface that names the status is a surface that has opinions about
         * the lifecycle, and the lifecycle is not the surface's.
         */
        fun pending(
            id: ProposalItemId,
            proposalId: ProposalId,
            kind: ProposalItemKind,
            targetId: String,
            humanSummary: String,
            fingerprint: String,
            sortOrder: Int = 0,
        ): ProposalItem = ProposalItem(
            id = id,
            proposalId = proposalId,
            kind = kind,
            targetId = targetId,
            humanSummary = humanSummary,
            status = ProposalItemStatus.Pending,
            fingerprint = fingerprint,
            sortOrder = sortOrder,
        )
    }
}
