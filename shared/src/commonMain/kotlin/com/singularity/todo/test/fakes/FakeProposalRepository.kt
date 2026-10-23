package com.singularity.todo.test.fakes

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.proposals.domain.logic.ProposalFingerprint
import com.singularity.todo.feature.proposals.domain.logic.ProposalStatusReducer
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.DecidedActor
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import com.singularity.todo.core.error.runCatchingCancellable

/**
 * In-memory [ProposalRepository].
 *
 * The compare-and-set in [claim] is reproduced faithfully rather than stubbed: a fake
 * that always returned the item would make the double-tap test in
 * `ApplyProposalItemUseCaseTest` pass for the wrong reason, which is worse than not
 * having the test at all.
 */
class FakeProposalRepository(private val clock: Clock) : ProposalRepository {

    private val proposals = MutableStateFlow<Map<String, AiProposal>>(emptyMap())
    private val items = MutableStateFlow<Map<String, ProposalItem>>(emptyMap())

    /**
     * What a save returns, when the test wants a save to fail.
     *
     * A proposal that was not stored must not be reported as stored, and the only
     * way to test that is a save that fails.
     */
    var saveOverride: Result<Unit>? = null

    /**
     * Runs just before a claim decides, so a test can change the row in between.
     *
     * The read and the claim are two moments, and the user can act in between: the
     * value they typed while the confirm was in flight is a new row. Null by default,
     * so an existing test changes nothing.
     */
    var beforeClaim: (suspend () -> Unit)? = null

    override fun watchProposalsForTask(taskId: TaskId): Flow<List<AiProposal>> = proposals.map { map ->
        map.values.filter { it.targetKind == AiProposal.TARGET_KIND_TASK && it.targetId == taskId.value }
            .sortedByDescending { it.createdAt }
            .map(::withItems)
    }

    override fun watchProposalsForNote(noteId: NoteId): Flow<List<AiProposal>> = proposals.map { map ->
        map.values.filter { it.targetKind == AiProposal.TARGET_KIND_NOTE && it.targetId == noteId.value }
            .sortedByDescending { it.createdAt }
            .map(::withItems)
    }

    override fun watchProposal(id: ProposalId): Flow<AiProposal?> =
        proposals.map { map -> map[id.value]?.let(::withItems) }

    override fun watchProposalsByStatus(status: ProposalStatus): Flow<List<AiProposal>> = proposals.map { map ->
        map.values.filter { it.status == status }
            .sortedByDescending { it.createdAt }
            .map(::withItems)
    }

    override fun watchProposalsByTargetKind(targetKind: String, status: ProposalStatus): Flow<List<AiProposal>> =
        proposals.map { map ->
            map.values.filter { it.targetKind == targetKind && it.status == status }
                .sortedByDescending { it.createdAt }
                .map(::withItems)
        }

    override suspend fun save(proposal: AiProposal): Result<Unit> = runCatchingCancellable {
        saveOverride?.let { return@runCatchingCancellable it.getOrThrow() }
        // The items first. A proposal's status is derived from its items, so a
        // proposal stored before its items would be derived as resolved — the
        // opposite of what a proposal with pending items is. The order is the
        // one the DAO has: items, then the row that counts them.
        proposal.items.forEach { item ->
            // Mirrors the unique (proposal_id, fingerprint) index.
            val clash = items.value.values.any {
                it.proposalId == item.proposalId && it.fingerprint == item.fingerprint && it.id != item.id
            }
            if (!clash) items.update { it + (item.id.value to item) }
        }
        proposals.update { it + (proposal.id.value to proposal.copy(status = derived(proposal.id.value))) }
    }

    override suspend fun getItem(id: ProposalItemId): ProposalItem? = items.value[id.value]

    /**
     * Runs after the claim was committed and before the call returns.
     *
     * The hook is where a test stands for the cancellation that arrives between
     * the transaction and the return. Null by default, so an existing test changes
     * nothing.
     */
    var afterClaim: (suspend () -> Unit)? = null

    /**
     * Rewrites a pending item's change, the way the DAO does.
     *
     * The summary and the fingerprint follow the kind: they belong to the value, so a
     * fake that left them behind would make a test pass with a card that reads one
     * thing and a fingerprint that means another.
     */
    override suspend fun updatePendingItem(
        id: ProposalItemId,
        kind: ProposalItemKind,
        userId: UserId,
    ): Result<ProposalItem?> = runCatchingCancellable {
        val stored = items.value[id.value] ?: return@runCatchingCancellable null
        if (stored.status != ProposalItemStatus.Pending) return@runCatchingCancellable null
        val owner = proposals.value[stored.proposalId.value]?.userId
        if (owner != userId) return@runCatchingCancellable null
        val rewritten = stored.copy(
            kind = kind,
            humanSummary = kind.humanSummary,
            fingerprint = ProposalFingerprint.of(kind, stored.targetId),
        )
        items.update { it + (id.value to rewritten) }
        rewritten
    }

    override suspend fun claim(
        id: ProposalItemId,
        status: ProposalItemStatus,
        actor: DecidedActor,
        reason: String?,
        userId: UserId,
    ): ProposalItem? {
        // A test can change the row in the moment between the read and the claim —
        // which is what the user typing a new value while the confirm is in flight
        // looks like from here.
        beforeClaim?.invoke()
        val before = items.value[id.value] ?: return null
        if (before.status != ProposalItemStatus.Pending) return null
        // Ownership is checked through the owning proposal, exactly as the DAO's
        // subquery does — a fake that skipped this would hide a cross-user write.
        if (proposals.value[before.proposalId.value]?.userId != userId) return null
        val decided = before.copy(
            status = status,
            decidedAt = clock.now(),
            decidedActor = actor,
            rejectionReason = reason,
        )
        items.update { it + (id.value to decided) }
        // The claim is committed and the call is still in flight. A scope that is
        // cancelled here must not leave the claim standing: the change waits again,
        // because nothing was written.
        afterClaim?.invoke()
        return decided
    }

    /**
     * Puts a confirmed item back into `Pending`.
     *
     * The mirror of the claim: only an item this user's claim confirmed goes back, and
     * only if nothing else has decided it since. A release that matched no row is a
     * failure, the way it is in the real repository — a fake that always succeeded here
     * would let a test pass where the production code throws.
     */
    override suspend fun release(id: ProposalItemId, userId: UserId): Result<Unit> = runCatchingCancellable {
        val before = items.value[id.value] ?: error("Proposal item ${id.value} not found")
        check(before.status == ProposalItemStatus.Confirmed) {
            "Proposal item ${id.value} is not confirmed"
        }
        check(before.decidedActor == DecidedActor.User) {
            "Proposal item ${id.value} was not confirmed by the user"
        }
        check(proposals.value[before.proposalId.value]?.userId == userId) {
            "Proposal item ${id.value} belongs to another user"
        }
        items.update { map ->
            map + (
                id.value to before.copy(
                    status = ProposalItemStatus.Pending,
                    decidedAt = null,
                    decidedActor = null,
                    rejectionReason = null,
                )
            )
        }
    }

    override suspend fun refreshStatus(proposalId: ProposalId, userId: UserId): Result<Unit> = runCatchingCancellable {
        val derived = derived(proposalId.value)
        proposals.update { map ->
            val row = map[proposalId.value] ?: return@update map
            if (row.userId != userId) return@update map
            map + (proposalId.value to row.copy(status = derived, updatedAt = clock.now()))
        }
    }

    override suspend fun retract(id: ProposalId, userId: UserId): Result<Unit> = runCatchingCancellable {
        val owned = proposals.value[id.value]?.userId == userId
        items.update { map ->
            map.mapValues { (key, value) ->
                if (owned && value.proposalId.value == id.value && value.status == ProposalItemStatus.Pending) {
                    value.copy(status = ProposalItemStatus.Retracted)
                } else {
                    value
                }
            }
        }
        proposals.update { map ->
            val row = map[id.value] ?: return@update map
            if (!owned) return@update map
            map + (id.value to row.copy(status = ProposalStatus.Retracted, updatedAt = clock.now()))
        }
    }

    override suspend fun rejectedFingerprints(userId: UserId, limit: Int): List<String> {
        val ownerIds = proposals.value.values.filter { it.userId == userId }.map { it.id }.toSet()
        return items.value.values
            .filter { it.proposalId in ownerIds && it.status == ProposalItemStatus.Rejected }
            .sortedByDescending { it.decidedAt }
            .take(limit)
            .map { it.fingerprint }
    }

    // ── Test helpers ──────────────────────────────────────────────────────────

    /** Current items for a proposal, in sort order. */
    fun itemsOf(proposalId: ProposalId): List<ProposalItem> =
        items.value.values.filter { it.proposalId.value == proposalId.value }.sortedBy { it.sortOrder }

    private fun withItems(proposal: AiProposal): AiProposal = proposal.copy(items = itemsOf(proposal.id))

    private fun derived(proposalId: String): ProposalStatus =
        ProposalStatusReducer.reduce(itemsOf(ProposalId(proposalId)).map { it.status })
}
