package com.singularity.todo.feature.proposals.data

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.DecidedActor
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.model.ProposalTargetKind
import kotlin.time.Instant

/**
 * Converts a persisted proposal row to the domain model.
 *
 * The row's own copy of the aggregate status is not read: the status is what the
 * changes say it is (see [AiProposal.status]), and a summary row written before
 * the decisions does not get to say otherwise.
 */
internal fun AiProposalEntity.toDomain(items: List<ProposalItem> = emptyList()): AiProposal = AiProposal(
    id = ProposalId(id),
    targetKind = ProposalTargetKind.read(targetKind),
    targetId = targetId,
    userId = UserId(userId),
    source = enumOrDefault(source, ProposalSource.Detail),
    createdAt = Instant.fromEpochMilliseconds(createdAt),
    updatedAt = Instant.fromEpochMilliseconds(updatedAt),
    items = items,
)

/**
 * Converts a persisted item row to the domain model.
 *
 * A `kind_json` blob this build cannot read is not a row to drop: it becomes a
 * [ProposalItemKind.Unknown], so the change stays on the card with the reason it
 * cannot be applied. One row the codec cannot make sense of must not make the
 * whole proposal card unrenderable — and it must not make the change disappear
 * either.
 *
 * The status is read the same way: strictly. A status this build does not know is
 * [ProposalItemStatus.Unknown], not "waiting" — a change that has already been
 * applied must not look like one that is still waiting to be applied.
 */
internal fun ProposalItemEntity.toDomain(): ProposalItem = ProposalItem(
    id = ProposalItemId(id),
    proposalId = ProposalId(proposalId),
    kind = decodeKind(kindJson),
    targetId = targetId,
    humanSummary = humanSummary,
    status = ProposalItemStatus.read(status),
    fingerprint = fingerprint,
    sortOrder = sortOrder,
    decidedAt = decidedAt?.let(Instant::fromEpochMilliseconds),
    decidedActor = decidedActor?.let { runCatching { DecidedActor.valueOf(it) }.getOrNull() },
    rejectionReason = rejectionReason,
)

/**
 * Parses an enum name from the database, falling back to [fallback].
 *
 * Only the source is read this way. The source says which surface produced the
 * proposal and gates nothing: a row whose source this build does not know is
 * still a proposal, and reading it as one of the known surfaces mis-attributes
 * it in the feedback loop without changing what happens to it. The statuses are
 * read strictly instead — a status decides whether a change may be applied.
 */
private inline fun <reified E : Enum<E>> enumOrDefault(raw: String, fallback: E): E =
    runCatching { enumValueOf<E>(raw) }.getOrDefault(fallback)
