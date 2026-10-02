package com.singularity.todo.feature.proposals.data

import com.singularity.todo.core.database.SyncColumns
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/**
 * Room-backed [ProposalRepository].
 *
 * Every read is scoped to a user id passed in by the caller rather than read from an
 * ambient current user, because a proposal is written and decided within a single
 * request and must not be able to observe a profile switch halfway through.
 */
class ProposalRepositoryImpl(
    private val dao: ProposalDao,
    private val items: ProposalItemDao,
    private val clock: Clock,
) : ProposalRepository {

    override fun watchProposalsForTask(taskId: TaskId, userId: UserId): Flow<List<AiProposal>> =
        dao.watchProposalsForTask(taskId.value, userId.value)
            .map { rows -> rows.map { hydrate(it) } }

    override fun watchProposal(id: ProposalId): Flow<AiProposal?> =
        combine(dao.watchProposal(id.value), items.watchItemsForProposal(id.value)) { row, items ->
            row?.let { it.toDomain(items.mapNotNull(ProposalItemEntity::toDomainOrNull)) }
        }

    override fun watchProposalsByStatus(userId: UserId, status: ProposalStatus): Flow<List<AiProposal>> =
        dao.watchProposalsByStatus(userId.value, status.name)
            .map { rows -> rows.map { hydrate(it) } }

    override suspend fun save(proposal: AiProposal): Result<Unit> = runCatching {
        val now = clock.now().toEpochMilliseconds()
        dao.upsertProposal(
            AiProposalEntity(
                id = proposal.id.value,
                taskId = proposal.taskId.value,
                userId = proposal.userId.value,
                source = proposal.source.name,
                status = ProposalStatusReducer.reduce(proposal.items.map { it.status }).name,
                createdAt = proposal.createdAt.toEpochMilliseconds(),
                updatedAt = now,
                sync = SyncColumns(),
            ),
        )
        proposal.items.forEach { item -> insertIfAbsent(item) }
    }

    override suspend fun getItem(id: ProposalItemId): ProposalItem? = items.getItem(id.value)?.toDomainOrNull()

    override suspend fun claim(
        id: ProposalItemId,
        status: ProposalItemStatus,
        actor: DecidedActor,
        reason: String?,
        userId: UserId,
    ): ProposalItem? = items.claimAndRead(
        id = id.value,
        userId = userId.value,
        status = status.name,
        decidedAt = clock.now().toEpochMilliseconds(),
        actor = actor.name,
        reason = reason,
    )?.toDomainOrNull()

    override suspend fun refreshStatus(proposalId: ProposalId, userId: UserId): Result<Unit> = runCatching {
        val items = items.getItemsForProposal(proposalId.value)
        val derived = ProposalStatusReducer.reduce(items.map { ProposalItemStatus.valueOf(it.status) })
        dao.updateProposalStatus(proposalId.value, derived.name, clock.now().toEpochMilliseconds(), userId.value)
    }

    override suspend fun retract(id: ProposalId, userId: UserId): Result<Unit> = runCatching {
        items.retractPendingItems(id.value, userId.value)
        dao.updateProposalStatus(
            id.value,
            ProposalStatus.Retracted.name,
            clock.now().toEpochMilliseconds(),
            userId.value,
        )
    }

    override suspend fun rejectedFingerprints(userId: UserId, limit: Int): List<String> =
        items.rejectedFingerprints(userId.value, limit)

    /**
     * Loads a proposal row with its items resolved.
     *
     * Items that fail to decode are dropped rather than failing the whole read: one
     * row written by a build that knew a kind we do not must not make every card on
     * the task unrenderable.
     */
    private suspend fun hydrate(row: AiProposalEntity): AiProposal =
        row.toDomain(items.getItemsForProposal(row.id).mapNotNull(ProposalItemEntity::toDomainOrNull))

    /**
     * Inserts an item unless its fingerprint is already on this proposal.
     *
     * The unique index on `(proposal_id, fingerprint)` is the real guarantee; this
     * check just avoids surfacing a constraint violation as an exception for a case
     * that is a normal outcome of regenerating a proposal.
     */
    private suspend fun insertIfAbsent(item: ProposalItem) {
        if (items.countItemsWithFingerprint(item.proposalId.value, item.fingerprint) > 0) return
        items.upsertItem(
            ProposalItemEntity(
                id = item.id.value,
                proposalId = item.proposalId.value,
                kindJson = item.kind.toJsonString(),
                targetId = item.targetId,
                humanSummary = item.humanSummary,
                status = item.status.name,
                fingerprint = item.fingerprint,
                sortOrder = item.sortOrder,
                decidedAt = item.decidedAt?.toEpochMilliseconds(),
                decidedActor = item.decidedActor?.name,
                rejectionReason = item.rejectionReason,
            ),
        )
    }
}
