package com.singularity.todo.feature.proposals.domain.port

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.DecidedActor
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
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

    /**
     * Watch every proposal on [noteId], newest first, with their items resolved.
     *
     * The note twin of [watchProposalsForTask]: an AI action on a note proposes a
     * change to that note, and a proposal nobody can reach is a proposal the user can
     * only answer by telling the assistant to stop.
     *
     * Scoped to the current user via [com.singularity.todo.feature.profile.ProfileAwareCurrentUser].
     * Emits again whenever a proposal or any of its items changes.
     */
    fun watchProposalsForNote(noteId: NoteId): Flow<List<AiProposal>>

    /** Watch one proposal with its items. Emits null if it does not exist. */
    fun watchProposal(id: ProposalId): Flow<AiProposal?>

    /**
     * Watch the proposals that still wait for a decision.
     *
     * The question is the domain's: a proposal is waiting while one of its changes
     * is undecided, and the repository answers it. A caller that asked for a
     * status instead would be asking the row — and the row's own copy of the
     * status is a cache that can disagree with the changes it summarizes.
     *
     * Scoped to the current user via [com.singularity.todo.feature.profile.ProfileAwareCurrentUser].
     */
    fun watchWaitingProposals(): Flow<List<AiProposal>>

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
     * Rewrites what a pending item asks for.
     *
     * The user may change the value of a proposal before it is applied, and the
     * changed value is stored with the summary the user reads and the fingerprint the
     * rejection guard compares — all three belong to the value, so all three change
     * together.
     *
     * Only a pending item can be rewritten: a decided item is a decision, and
     * rewriting it would rewrite what the user already decided.
     *
     * @return the rewritten item, or null when the item was not pending (any more).
     */
    suspend fun updatePendingItem(
        id: ProposalItemId,
        kind: ProposalItemKind,
        userId: UserId,
    ): Result<ProposalItem?>

    /**
     * Transition an item out of the status it is expected to carry, via compare-and-set.
     *
     * @param status the status the item goes to.
     * @param expected the status the row has to carry for this call to win the race —
     *   [ProposalItemStatus.Pending] for a change this build can apply, and the status
     *   it was read with for a change whose status this build cannot read. A turn-down
     *   has to be able to reach that second kind: the user is refusing a change they
     *   cannot apply, and a refusal they cannot make is a card they cannot clear.
     * @return the decided item if this call won the race, or null if the item was
     *   already decided. A null return is the normal outcome of a double-tap and is
     *   **not** an error: the caller must apply nothing.
     */
    suspend fun claim(
        id: ProposalItemId,
        status: ProposalItemStatus,
        expected: ProposalItemStatus,
        actor: DecidedActor,
        reason: String?,
        userId: UserId,
    ): ProposalItem?

    /**
     * Puts a claimed item back into [ProposalItemStatus.Pending].
     *
     * The claim is the decision; the dispatch is the work. When the work fails, the
     * decision must not stand on its own — nothing was changed, so the item goes back
     * to `Pending` and the user can try again.
     *
     * Only an item this user's claim confirmed can be released, and only if nothing
     * else has decided it since.
     */
    suspend fun release(id: ProposalItemId, userId: UserId): Result<Unit>

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
