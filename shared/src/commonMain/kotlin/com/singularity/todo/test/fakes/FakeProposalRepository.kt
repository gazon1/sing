package com.singularity.todo.test.fakes

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.proposals.domain.logic.ProposalStatusReducer
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.DecidedActor
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

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

    override fun watchProposalsForTask(taskId: TaskId, userId: UserId): Flow<List<AiProposal>> = proposals.map { map ->
        map.values.filter { it.taskId == taskId && it.userId == userId }
            .sortedByDescending { it.createdAt }
            .map(::withItems)
    }

    override fun watchProposal(id: ProposalId): Flow<AiProposal?> =
        proposals.map { map -> map[id.value]?.let(::withItems) }

    override fun watchProposalsByStatus(userId: UserId, status: ProposalStatus): Flow<List<AiProposal>> =
        proposals.map { map ->
            map.values.filter { it.userId == userId && it.status == status }
                .sortedByDescending { it.createdAt }
                .map(::withItems)
        }

    override suspend fun save(proposal: AiProposal): Result<Unit> = runCatching {
        proposals.update { it + (proposal.id.value to proposal.copy(status = derived(proposal.id.value))) }
        proposal.items.forEach { item ->
            // Mirrors the unique (proposal_id, fingerprint) index.
            val clash = items.value.values.any {
                it.proposalId == item.proposalId && it.fingerprint == item.fingerprint && it.id != item.id
            }
            if (!clash) items.update { it + (item.id.value to item) }
        }
    }

    override suspend fun getItem(id: ProposalItemId): ProposalItem? = items.value[id.value]

    override suspend fun claim(
        id: ProposalItemId,
        status: ProposalItemStatus,
        actor: DecidedActor,
        reason: String?,
        userId: UserId,
    ): ProposalItem? {
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
        return decided
    }

    override suspend fun refreshStatus(proposalId: ProposalId, userId: UserId): Result<Unit> = runCatching {
        val derived = derived(proposalId.value)
        proposals.update { map ->
            val row = map[proposalId.value] ?: return@update map
            if (row.userId != userId) return@update map
            map + (proposalId.value to row.copy(status = derived, updatedAt = clock.now()))
        }
    }

    override suspend fun retract(id: ProposalId, userId: UserId): Result<Unit> = runCatching {
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
