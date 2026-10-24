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
 *
 * ## Why this is not an enum
 *
 * A status this build does not know used to be read as [Pending] — the reader
 * filled the gap with the state it knew, and a change that had already been
 * applied looked like one that was still waiting. The apply use case asks exactly
 * one question — "is this still pending?" — so a wrong answer there is a change
 * applied twice.
 *
 * So the reader does not fill the gap: a status it cannot name is [Unknown], which
 * is neither pending nor decided. The change stays on the card with the reason it
 * cannot be applied, and the user can still turn it down.
 */
sealed interface ProposalItemStatus {

    /**
     * The value the row carries.
     *
     * The same four values the row has always carried, so a row written before
     * this type existed reads as what it says it is.
     */
    val name: String

    /** Awaiting the user's decision. */
    data object Pending : ProposalItemStatus {
        override val name: String = "Pending"
    }

    /** Accepted — the change has been applied to the task. */
    data object Confirmed : ProposalItemStatus {
        override val name: String = "Confirmed"
    }

    /** Declined — nothing was applied. [ProposalItem.rejectionReason] may explain why. */
    data object Rejected : ProposalItemStatus {
        override val name: String = "Rejected"
    }

    /** Withdrawn along with the owning proposal. */
    data object Retracted : ProposalItemStatus {
        override val name: String = "Retracted"
    }

    /**
     * A status this build does not know.
     *
     * The row was written by a build that knew a status this one does not. The
     * change is not lost and it is not applied: it is a change whose state is
     * unknown, and the reader says so in the words the user reads.
     *
     * @property raw The status as it was stored, kept so the row can be written
     *   back unchanged — a row this build cannot read must not become a row this
     *   build rewrites into something else.
     */
    data class Unknown(val raw: String) : ProposalItemStatus {
        override val name: String get() = raw
    }

    companion object {

        /** The statuses this build knows. */
        private val known: List<ProposalItemStatus> = listOf(Pending, Confirmed, Rejected, Retracted)

        /**
         * Reads a stored status.
         *
         * A name this build does not know is [Unknown] — never [Pending]. Reading
         * an unknown status as "waiting" is how an applied change gets applied a
         * second time.
         */
        fun read(raw: String): ProposalItemStatus = known.firstOrNull { it.name == raw } ?: Unknown(raw)
    }
}

/**
 * True while the change waits for the user's decision.
 *
 * Exactly [ProposalItemStatus.Pending] and nothing else: a change whose status
 * this build cannot read is not waiting, it is unknown — and an unknown change is
 * not one this build may apply.
 */
val ProposalItemStatus.isPending: Boolean get() = this == ProposalItemStatus.Pending

/**
 * True when the user has already decided about the change.
 *
 * A change whose status this build cannot read has *not* been decided — the user
 * never said yes and never said no, so the change is still the user's to turn
 * down.
 */
val ProposalItemStatus.isDecided: Boolean
    get() = this == ProposalItemStatus.Confirmed ||
        this == ProposalItemStatus.Rejected ||
        this == ProposalItemStatus.Retracted

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
     *
     * The status is the other thing that can stop a change from reading: a row
     * whose status this build does not know says so here, rather than reading as
     * a change that is still waiting.
     */
    val readFailure: String? get() = kind.readFailure ?: statusFailure

    /**
     * Why this change cannot be applied, when its stored status does not read.
     *
     * The reader names the reason it knows: a status this build does not know is
     * one reason, and it is not the same reason as a value that does not read.
     */
    val statusFailure: String?
        get() = when (status) {
            is ProposalItemStatus.Unknown ->
                "This change was written with the status «${status.raw}», " +
                    "which this version of the app does not know."

            else -> null
        }

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
            is ProposalItemStatus.Unknown -> "Unknown"
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
