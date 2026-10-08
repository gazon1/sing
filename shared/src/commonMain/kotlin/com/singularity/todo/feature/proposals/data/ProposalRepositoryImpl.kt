package com.singularity.todo.feature.proposals.data

import co.touchlab.kermit.Logger
import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.logic.ProposalStatusReducer
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.DecidedActor
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import com.singularity.todo.core.error.runCatchingCancellable

/**
 * Room-backed [ProposalRepository].
 *
 * All observe methods are self-scoped via [ProfileAwareCurrentUser.scopedUserId].
 * When the current profile switches, `flatMapLatest` automatically re-subscribes
 * with the new user partition — callers do NOT pass `userId` as a parameter.
 *
 * The `scopedUserId.value` snapshot inside `save` is intentional: a proposal is
 * written and decided within a single request and must not observe a profile
 * switch halfway through.
 */
@Suppress("TooManyFunctions")
@OptIn(ExperimentalCoroutinesApi::class)
class ProposalRepositoryImpl(
    private val dao: ProposalDao,
    private val items: ProposalItemDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
    private val log: Logger = Logger.withTag("ProposalRepositoryImpl"),
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
) : ProposalRepository {

    override fun watchProposalsForTask(taskId: TaskId): Flow<List<AiProposal>> =
        currentUser.scopedUserId.flatMapLatest { userId ->
            dao.watchProposalsForTarget(taskId.value, AiProposal.TARGET_KIND_TASK, userId.value)
                .map { rows -> rows.map { row -> hydrate(row) } }
        }

    override fun watchProposal(id: ProposalId): Flow<AiProposal?> =
        combine(dao.watchProposal(id.value), items.watchItemsForProposal(id.value)) { row, items ->
            row?.let { it.toDomain(items.mapNotNull(ProposalItemEntity::toDomainOrNull)) }
        }

    override fun watchProposalsByStatus(status: ProposalStatus): Flow<List<AiProposal>> =
        currentUser.scopedUserId.flatMapLatest { userId ->
            dao.watchProposalsByStatus(userId.value, status.name)
                .map { rows -> rows.map { row -> hydrate(row) } }
        }

    override fun watchProposalsByTargetKind(targetKind: String, status: ProposalStatus): Flow<List<AiProposal>> =
        currentUser.scopedUserId.flatMapLatest { userId ->
            dao.watchProposalsByTargetKind(userId.value, targetKind, status.name)
                .map { rows -> rows.map { row -> hydrate(row) } }
        }

    override suspend fun save(proposal: AiProposal): Result<Unit> = runCatchingCancellable {
        val now = clock.now().toEpochMilliseconds()
        dao.upsertProposal(
            AiProposalEntity(
                id = proposal.id.value,
                userId = proposal.userId.value,
                source = proposal.source.name,
                status = ProposalStatusReducer.reduce(proposal.items.map { it.status }).name,
                targetKind = proposal.targetKind,
                targetId = proposal.targetId,
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

    /**
     * Recomputes a proposal's aggregate status from its items.
     *
     * Reported here rather than at the three `ApplyProposalItemUseCase` call sites that
     * discard this `Result`, for the reason REQ-OS-028 set for `SyncEngine.enqueue`:
     * three identical calls in one class, each already handling the `Result` of the write
     * immediately before it. Three error paths would repeat the sentence and still be
     * silent at the next call site someone writes.
     *
     * Reported rather than thrown. The items are already decided correctly in the
     * database; only the summary row did not get recomputed. Throwing would fail a
     * confirm that genuinely succeeded, over a derived value — so the failure is made
     * visible and the next confirm or reject retries the recomputation.
     *
     * @see REQ-PROP-001
     */
    override suspend fun refreshStatus(proposalId: ProposalId, userId: UserId): Result<Unit> =
        // Explicit <Unit>: the last expression is `dao.updateProposalStatus`, which returns
        // Int, and chaining `.also` stops the declared return type from propagating into the
        // inference. Without it this compiles to Result<Int> and fails to assign.
        runCatchingCancellable<Unit> {
            val items = items.getItemsForProposal(proposalId.value)
            val derived = ProposalStatusReducer.reduce(items.map { ProposalItemStatus.valueOf(it.status) })
            dao.updateProposalStatus(proposalId.value, derived.name, clock.now().toEpochMilliseconds(), userId.value)
        }.also { result ->
            val error = result.exceptionOrNull() ?: return@also
            log.e(error) {
                "proposal ${proposalId.value}: items were decided but the aggregate status " +
                    "was not recomputed, so the proposal keeps its previous status"
            }
            crashReporter.report(error, "proposals.refresh_status_failed")
        }

    override suspend fun retract(id: ProposalId, userId: UserId): Result<Unit> = runCatchingCancellable {
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
