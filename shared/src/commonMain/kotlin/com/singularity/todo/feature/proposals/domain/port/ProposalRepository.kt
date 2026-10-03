package com.singularity.todo.feature.proposals.domain.port

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.DecidedActor
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for AI proposals and their individually-decidable items.
 *
 * Proposals are local-only. They are a staging area for a change that has *not* been
 * agreed to, and an unagreed change has no business existing on another device, so
 * nothing here writes to the sync outbox. The `sync` columns exist so that changing
 * this decision later is a code change, not a schema migration.
 *
 * ## Observation scoping
 *
 * All observe methods are self-scoped via [com.singularity.todo.feature.profile.ProfileAwareCurrentUser].
 * Callers MUST NOT pass `userId`/`scopedUserId` as parameters to observe methods — the
 * repository reads it from the ambient current user. This contract is enforced by
 * [com.singularity.todo.core.detekt.ProhibitUserIdInObserve] (Konsist).
 */
interface ProposalRepository {

    /**
     * Watch every proposal on [taskId], newest first, with their items resolved.
     * Scoped to the current user via [com.singularity.todo.feature.profile.ProfileAwareCurrentUser].
     * Emits again whenever a proposal or any of its items changes.
     */
    fun watchProposalsForTask(taskId: TaskId): Flow<List<AiProposal>>

    /** Watch one proposal with its items. Emits null if it does not exist. */
    fun watchProposal(id: ProposalId): Flow<AiProposal?>

    /**
     * Watch proposals in a given aggregate [status].
     * Scoped to the current user via [com.singularity.todo.feature.profile.ProfileAwareCurrentUser].
     */
    fun watchProposalsByStatus(status: ProposalStatus): Flow<List<AiProposal>>

    /**
     * Watch proposals for a specific [targetKind] (e.g. [com.singularity.todo.feature.proposals.domain.model.AiProposal.TARGET_KIND_TASK])
     * in a given aggregate [status].
     * Scoped to the current user via [com.singularity.todo.feature.profile.ProfileAwareCurrentUser].
     */
    fun watchProposalsByTargetKind(targetKind: String, status: ProposalStatus): Flow<List<AiProposal>>

    /**
     * Persist a proposal together with its items, replacing any items already stored
     * for the same proposal id.
     *
     * Items whose fingerprint is already present for this proposal are skipped rather
     * than re-inserted, so re-running generation is idempotent.
     */
    suspend fun save(proposal: AiProposal): Result<Unit>

    /** Read one item. Used by the apply use case to re-read before writing. */
    suspend fun getItem(id: ProposalItemId): ProposalItem?

    /**
     * Transition an item out of [ProposalItemStatus.Pending] via compare-and-set.
     *
     * @return the decided item if this call won the race, or null if the item was
     *   already decided. A null return is the normal outcome of a double-tap and is
     *   **not** an error: the caller must apply nothing.
     */
    suspend fun claim(
        id: ProposalItemId,
        status: ProposalItemStatus,
        actor: DecidedActor,
        reason: String?,
        userId: UserId,
    ): ProposalItem?

    /**
     * Recompute the owning proposal's aggregate [ProposalStatus] from its items and
     * persist it. Called after every decision.
     */
    suspend fun refreshStatus(proposalId: ProposalId, userId: UserId): Result<Unit>

    /** Withdraw a proposal and every one of its undecided items. */
    suspend fun retract(id: ProposalId, userId: UserId): Result<Unit>

    /**
     * Fingerprints the user has already rejected, newest first.
     *
     * Assembly consults this to refuse re-proposing a change that was turned down.
     * The prompt-side "recently rejected" list is built from the same source, so the
     * hint the model sees and the rule it is held to cannot drift apart.
     */
    suspend fun rejectedFingerprints(userId: UserId, limit: Int = 50): List<String>
}
