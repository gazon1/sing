package com.singularity.todo.feature.ai.tools

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.proposals.domain.logic.ProposalBuilder
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlin.time.Clock

/**
 * The shared path of every write tool: the change becomes a proposal, and the
 * proposal waits for the user.
 *
 * ## Why the tools share it
 *
 * Six tools propose the same kind of thing — a change to the user's data that
 * nobody has agreed to yet. Each of them used to write on its own, and each of
 * them used to build its own proposal by hand. A hand-built proposal is a
 * proposal that can forget its summary or its fingerprint, and a proposal
 * without a fingerprint is a proposal the user can turn down and be asked
 * about again. So the building is here, once.
 *
 * @param items The changes the proposal holds. One item per change, so the user
 *   reads "Update title" and "Set priority" and decides about each.
 * @param targetKind What the change is about — a task, a note, a project.
 * @param targetId The thing the change is about. For a creation this is the id
 *   the thing *will* have: it does not exist yet, and the proposal says which
 *   one it will be.
 */
internal suspend fun proposeChange(
    proposals: ProposalRepository,
    userId: UserId,
    clock: Clock,
    items: List<ProposalItem>,
    targetKind: String,
    targetId: String,
): Result<AiProposal> = runCatchingCancellable {
    val (proposal, _) = ProposalBuilder.multiForTarget(
        items = items,
        targetKind = targetKind,
        targetId = targetId,
        userId = userId,
        source = ProposalSource.Agent,
        clock = clock,
    )
    proposals.save(proposal).getOrThrow()
    proposal
}

/**
 * One proposal item per change, with the summary the user reads and the
 * fingerprint the machinery compares.
 */
internal fun proposalItems(kinds: List<ProposalItemKind>, targetId: String): List<ProposalItem> =
    kinds.map { ProposalBuilder.item(it, targetId) }

/**
 * The priority in the spelling a task can hold, or `null` if the name is not one.
 *
 * The model writes "high"; the task holds `High`. Spelling it once here is what
 * keeps a proposal applicable: a proposal that passes the tool and then fails to
 * apply is a proposal that burned the user's attention for nothing.
 */
internal fun canonicalPriority(raw: String): TaskPriority? =
    TaskPriority.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

/** The kind in the spelling a task can hold, or `null` if the name is not one. */
internal fun canonicalKind(raw: String): TaskKind? =
    TaskKind.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

/** The names a priority can have, as the model sees them. */
internal const val PRIORITY_NAMES = "Use none, low, medium, high or urgent."

/** The names a kind of work can have, as the model sees them. */
internal const val KIND_NAMES = "Use task or note."
