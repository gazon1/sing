package com.singularity.todo.feature.proposals.domain.usecase

import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.proposals.domain.model.DecidedActor
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.first

/**
 * Applies or refuses one AI-proposed change.
 *
 * ## The ordering, and why it is this ordering
 *
 * 1. **Read.** The item is re-read from the store, not taken from a value the UI is
 *    holding. The agent decided against whatever the task looked like when it
 *    proposed; writing its conclusion over a field that has since changed would
 *    silently clobber the user's edit. This mirrors the `writeOnStored` discipline
 *    in ADR 0103.
 * 2. **Plan.** Every parse and every precondition check happens in [ProposalPlanner],
 *    *before* anything is claimed. A malformed or unapplicable proposal therefore leaves
 *    the item `Pending` and re-tryable, rather than half-applied with a decision recorded.
 * 3. **Claim.** A compare-and-set moves the item out of `Pending`. Losing that race
 *    means another tap already decided it, and this call then does nothing at all.
 * 4. **Dispatch** against the row the claim read back, not an earlier read.
 * 5. **Refresh** the proposal's aggregate status.
 *
 * Reject is steps 1, 3 and 5 — no dispatch, but the reason is persisted so the
 * prompt's "recently rejected" list can quote it back.
 */
class ApplyProposalItemUseCase(
    private val proposals: ProposalRepository,
    private val tasks: TaskRepository,
    private val notes: NotesRepository,
    private val tags: TagsRepository,
    private val planner: ProposalPlanner,
    private val dispatch: ProposalDispatch,
) {

    /**
     * Confirms [itemId]: claims it, then applies the change.
     *
     * @return the item in its post-decision state. A failure is a precondition or
     *   dispatch error, in which case the item is still `Pending` and nothing changed.
     */
    suspend fun confirm(itemId: ProposalItemId, userId: UserId): Result<ProposalItem> = runCatching {
        val stored = proposals.getItem(itemId) ?: error("Proposal item $itemId not found")
        check(stored.status == ProposalItemStatus.Pending) {
            "Proposal item $itemId is already ${stored.status.name}"
        }

        // Plan first: a proposal that cannot be applied must not consume its one
        // irreversible decision. All I/O (fetch task/note, resolve tag ids) happens here
        // before the claim, so bad proposals fail fast.
        val plan = buildPlan(stored)

        val claimed = proposals.claim(itemId, ProposalItemStatus.Confirmed, DecidedActor.User, null, userId)
            ?: return@runCatching stored // lost the race — someone else decided it
        dispatch.dispatch(plan, userId)
        proposals.refreshStatus(claimed.proposalId, userId)
        claimed
    }

    /**
     * Rejects [itemId], optionally recording [reason].
     *
     * Nothing is applied. The reason is kept so the feedback builder can quote it
     * back to the model; a reason under [MIN_REASON_LENGTH] characters is stored
     * anyway but is not treated as usable feedback, because a one-word "no" tells
     * the model nothing it can act on.
     */
    suspend fun reject(itemId: ProposalItemId, userId: UserId, reason: String? = null): Result<ProposalItem> =
        runCatching {
            val stored = proposals.getItem(itemId) ?: error("Proposal item $itemId not found")
            val claimed = proposals.claim(
                itemId,
                ProposalItemStatus.Rejected,
                DecidedActor.User,
                reason?.trim()?.takeIf { it.isNotEmpty() },
                userId,
            ) ?: return@runCatching stored
            proposals.refreshStatus(claimed.proposalId, userId)
            claimed
        }

    /**
     * Confirms every pending item on a proposal.
     *
     * Each item goes through [confirm] independently, so one that cannot be applied
     * fails on its own and does not stop the rest. The return value is therefore
     * (applied, failed) rather than a single Result — a batch where three of four
     * succeed is the normal case, not an error.
     */
    suspend fun confirmAll(proposalId: com.singularity.todo.core.ids.ProposalId, userId: UserId): BatchResult {
        val pending = proposals.watchProposal(proposalId).first()?.items.orEmpty()
            .filter { it.status == ProposalItemStatus.Pending }
        val applied = mutableListOf<ProposalItem>()
        val failed = mutableListOf<Throwable>()
        pending.forEach { item ->
            confirm(item.id, userId).fold(
                onSuccess = { applied += it },
                onFailure = { failed += it },
            )
        }
        proposals.refreshStatus(proposalId, userId)
        return BatchResult(applied, failed)
    }

    /** Outcome of a batch confirm — partial success is normal, not exceptional. */
    data class BatchResult(val applied: List<ProposalItem>, val failed: List<Throwable>)

    // ── Planning ──────────────────────────────────────────────────────────────

    /**
     * Builds a [ProposalPlan] from a stored item.
     *
     * All I/O (fetching the target task/note, resolving tag ids) happens here,
     * before the claim. This lets the planner fail fast without burning the
     * one irreversible decision.
     */
    private suspend fun buildPlan(item: ProposalItem): ProposalPlan {
        return when {
            item.kind.isNoteBound -> {
                // SetNoteField needs the note; DeleteNote and ExtractActions don't but
                // passing the note for SetNoteField is what the planner needs.
                val note = notes.get(NoteId(item.targetId))
                planner.planNoteItem(item, note)
            }

            item.kind.isDeleteVariant -> planner.planDeleteItem(item)

            else -> {
                // Fetch task and resolve tag ids before calling the planner.
                val task = tasks.get(TaskId(item.targetId))
                val resolvedTagIds = item.kind.resolveTagIds()
                planner.planTaskItem(item, task, resolvedTagIds)
            }
        }
    }

    /** True when this kind targets a note rather than a task. */
    private val ProposalItemKind.isNoteBound: Boolean
        get() = this is ProposalItemKind.SetNoteField ||
            this is ProposalItemKind.DeleteNote ||
            this is ProposalItemKind.ExtractActions

    /** True when this kind is a delete operation for a non-note entity. */
    private val ProposalItemKind.isDeleteVariant: Boolean
        get() = this is ProposalItemKind.DeleteTask ||
            this is ProposalItemKind.DeleteProject ||
            this is ProposalItemKind.DeleteTag

    /**
     * Resolves tag ids for AddTags/RemoveTags kinds.
     *
     * Called only during planning, before the claim — same as the original
     * `resolveTagIds` method. The tag names are looked up here so the planner
     * remains pure.
     */
    private suspend fun ProposalItemKind.resolveTagIds(): Set<TagId> {
        val names = when (this) {
            is ProposalItemKind.AddTags -> names
            else -> return emptySet()
        }
        if (names.isEmpty()) return emptySet()
        val byName = tags.observeAll().first().associateBy { it.name.trim().lowercase() }
        return names.mapNotNull { byName[it.trim().lowercase()]?.id }.toSet()
    }

    /** Shortest rejection reason worth feeding back to the model. */
    companion object {
        const val MIN_REASON_LENGTH = 20
    }
}
