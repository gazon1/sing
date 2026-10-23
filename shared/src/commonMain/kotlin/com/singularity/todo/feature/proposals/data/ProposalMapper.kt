package com.singularity.todo.feature.proposals.data

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.DecidedActor
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
import kotlin.time.Instant

/** Converts a persisted proposal row to the domain model. */
internal fun AiProposalEntity.toDomain(items: List<ProposalItem> = emptyList()): AiProposal = AiProposal(
    id = ProposalId(id),
    targetKind = targetKind,
    targetId = targetId,
    userId = UserId(userId),
    source = enumOrDefault(source, ProposalSource.Detail),
    status = enumOrDefault(status, ProposalStatus.Pending),
    createdAt = Instant.fromEpochMilliseconds(createdAt),
    updatedAt = Instant.fromEpochMilliseconds(updatedAt),
    items = items,
)

/**
 * Converts a persisted item row to the domain model.
 *
 * A `kind_json` blob that fails to parse yields `null` rather than throwing: one
 * corrupt row must not make the whole proposal card unrenderable, and the item is
 * already decided-or-pending state that the user can still act on via the rest of
 * the card. The caller filters nulls out.
 */
internal fun ProposalItemEntity.toDomainOrNull(): ProposalItem? {
    val kind = decodeKind(kindJson) ?: return null
    return ProposalItem(
        id = ProposalItemId(id),
        proposalId = ProposalId(proposalId),
        kind = kind,
        targetId = targetId,
        humanSummary = humanSummary,
        status = enumOrDefault(status, ProposalItemStatus.Pending),
        fingerprint = fingerprint,
        sortOrder = sortOrder,
        decidedAt = decidedAt?.let(Instant::fromEpochMilliseconds),
        decidedActor = decidedActor?.let { runCatching { DecidedActor.valueOf(it) }.getOrNull() },
        rejectionReason = rejectionReason,
    )
}

/**
 * Parses an enum name from the database, falling back to [fallback].
 *
 * A row written by a build that knew a status we do not (or vice versa) should render
 * as something sensible rather than crash the card.
 */
private inline fun <reified E : Enum<E>> enumOrDefault(raw: String, fallback: E): E =
    runCatching { enumValueOf<E>(raw) }.getOrDefault(fallback)
