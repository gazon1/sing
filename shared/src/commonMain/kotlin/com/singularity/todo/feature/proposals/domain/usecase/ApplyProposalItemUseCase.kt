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
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.singularity.todo.core.error.runCatchingCancellable

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
 * 4. **Dispatch** against the row the claim read back, not an earlier read. A dispatch
 *    that fails puts the item back into `Pending`: the work did not happen, so the
 *    decision must not stand on its own — and the reason is reported next to the
 *    change, where the user can read it and try again.
 * 5. **Refresh** the proposal's aggregate status.
 *
 * Reject is steps 1, 3 and 5 — no dispatch, but the reason is persisted so the
 * prompt's "recently rejected" list can quote it back.
 */
class ApplyProposalItemUseCase(
    private val proposals: ProposalRepository,
    private val tasks: TaskRepository,
    private val notes: NotesRepository,
    private val projects: ProjectsRepository,
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
    suspend fun confirm(itemId: ProposalItemId, userId: UserId): Result<ProposalItem> {
        // Whether the claim is standing, and whether it still has to be given back.
        var claimed = false
        var settled = false
        try {
            return runCatchingCancellable {
                val stored = proposals.getItem(itemId) ?: error("Proposal item $itemId not found")
                check(stored.status == ProposalItemStatus.Pending) {
                    "Proposal item $itemId is already ${stored.status.name}"
                }

                // Plan first: a proposal that cannot be applied must not consume its one
                // irreversible decision. All I/O (fetch task/note, resolve tag ids) happens
                // here before the claim, so bad proposals fail fast.
                val plan = buildPlan(stored, userId)

                // The claim is a transaction, and what it decided is known inside
                // it. The flag is set where the transaction is, so a cancellation
                // that arrives between the commit and the return cannot leave the
                // claim behind: the flag says the claim is standing, and a standing
                // claim is given back.
                // A scope that is already gone does not claim: the claim is the
                // decision, and there is nobody left to make it.
                currentCoroutineContext().ensureActive()
                val claimedItem = withContext(NonCancellable) {
                    val item = proposals.claim(
                        itemId,
                        ProposalItemStatus.Confirmed,
                        DecidedActor.User,
                        null,
                        userId,
                    )
                    if (item != null) claimed = true
                    item
                }
                    // Lost the race — the other tap decided it. Re-read, so the caller is
                    // told what the item actually is now, instead of a stale pending copy.
                    ?: return@runCatchingCancellable proposals.getItem(itemId) ?: stored

                // Everything that can fail after the claim happens inside this block: the
                // rebuild of a stale plan and the dispatch itself. A failure that escaped it
                // would leave the item confirmed although nothing was written — the very
                // thing the release below exists to prevent.
                val applied = runCatchingCancellable {
                    // The plan was built from the row read before the claim. If the row
                    // changed in between — the user edited the value while the confirm was
                    // in flight — the plan is stale, and dispatching it would write the old
                    // value over the new one.
                    val effective = if (claimedItem.kind == stored.kind) plan else buildPlan(claimedItem, userId)
                    dispatch.dispatch(effective, userId)
                }
                if (applied.isFailure) {
                    // The claim is the decision; the dispatch is the work. The work failed,
                    // so the decision must not stand on its own: the item goes back to
                    // Pending and the reason is reported next to it, where the user can read
                    // it and retry.
                    val failure = applied.exceptionOrNull()
                        ?: IllegalStateException("Proposal item $itemId could not be applied")
                    val released = releaseClaim(itemId, userId)
                    settled = true
                    throw released.exceptionOrNull()?.let { releaseFailure ->
                        IllegalStateException(
                            "${failure.message}; and the change could not be put back: ${releaseFailure.message}",
                            failure,
                        )
                    } ?: failure
                }
                proposals.refreshStatus(claimedItem.proposalId, userId)
                settled = true
                claimedItem
            }
        } finally {
            if (claimed && !settled) {
                // The claim is given back even when the scope was cancelled — the screen
                // went away in the middle of the work. A change nobody applied must not
                // stay decided. The release runs in a context that is not cancelled any
                // more, and its failure is reported by the repository: replacing the
                // cancellation with it would be worse than the release not happening.
                releaseClaim(itemId, userId).getOrNull()
            }
        }
    }

    /**
     * Puts a claimed item back into `Pending`.
     *
     * The release runs in a context that is not cancelled: a cancellation is exactly the
     * moment where the claim would otherwise be left behind.
     */
    private suspend fun releaseClaim(itemId: ProposalItemId, userId: UserId): Result<Unit> =
        withContext(NonCancellable) { proposals.release(itemId, userId) }

    /**
     * Rejects [itemId], optionally recording [reason].
     *
     * Nothing is applied. The reason is kept so the feedback builder can quote it
     * back to the model; a reason under [MIN_REASON_LENGTH] characters is stored
     * anyway but is not treated as usable feedback, because a one-word "no" tells
     * the model nothing it can act on.
     */
    suspend fun reject(itemId: ProposalItemId, userId: UserId, reason: String? = null): Result<ProposalItem> =
        runCatchingCancellable {
            val stored = proposals.getItem(itemId) ?: error("Proposal item $itemId not found")
            val claimed = proposals.claim(
                itemId,
                ProposalItemStatus.Rejected,
                DecidedActor.User,
                reason?.trim()?.takeIf { it.isNotEmpty() },
                userId,
            ) ?: return@runCatchingCancellable stored
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
        val failed = mutableListOf<FailedItem>()
        pending.forEach { item ->
            confirm(item.id, userId).fold(
                onSuccess = { applied += it },
                onFailure = { failed += FailedItem(item.id, it) },
            )
        }
        proposals.refreshStatus(proposalId, userId)
        return BatchResult(applied, failed)
    }

    /** Outcome of a batch confirm — partial success is normal, not exceptional. */
    data class BatchResult(val applied: List<ProposalItem>, val failed: List<FailedItem>)

    /**
     * One change of a batch that could not be applied.
     *
     * The item is named, so the reason can be shown next to the change it belongs to
     * instead of as one message about a batch.
     */
    data class FailedItem(val itemId: ProposalItemId, val error: Throwable)

    // ── Planning ──────────────────────────────────────────────────────────────

    /**
     * Builds a [ProposalPlan] from a stored item.
     *
     * All I/O (fetching the target task/note, resolving tag ids) happens here,
     * before the claim. This lets the planner fail fast without burning the
     * one irreversible decision.
     */
    private suspend fun buildPlan(item: ProposalItem, userId: UserId): ProposalPlan {
        // A change this build cannot read is refused here — before the claim — and
        // the reason it carries is the reason the user reads. The change stays on
        // the card: it is not applied, it is not lost.
        val kind = item.kind
        if (kind is ProposalItemKind.Unknown) {
            error(kind.readFailure ?: "This change cannot be read.")
        }
        return when {
            kind.isCreateVariant -> planner.planCreateItem(item, userId)

            kind.isNoteBound -> {
                // SetNoteField needs the note; DeleteNote and ExtractActions don't but
                // passing the note for SetNoteField is what the planner needs.
                val note = notes.get(NoteId(item.targetId))
                planner.planNoteItem(item, note)
            }

            kind.isTagBound -> {
                val tag = tags.get(TagId.fromString(item.targetId))
                planner.planTagItem(item, tag)
            }

            kind.isProjectBound -> {
                val project = projects.get(ProjectId.fromString(item.targetId))
                planner.planProjectItem(item, project)
            }

            kind.isDeleteVariant -> planner.planDeleteItem(item)

            else -> {
                // Fetch task and resolve tag ids before calling the planner.
                val task = tasks.get(TaskId(item.targetId))
                val resolvedTagIds = kind.resolveTagIds()
                planner.planTaskItem(item, task, resolvedTagIds)
            }
        }
    }

    /**
     * True when this kind creates something that does not exist yet.
     *
     * A creation has no target to fetch: the proposal named the id the thing *will*
     * have, so there is nothing to read before it is built.
     */
    private val ProposalItemKind.isCreateVariant: Boolean
        get() = this is ProposalItemKind.CreateTask ||
            this is ProposalItemKind.CreateProject ||
            this is ProposalItemKind.CreateNote ||
            this is ProposalItemKind.CreateTag

    /** True when this kind targets a tag. */
    private val ProposalItemKind.isTagBound: Boolean
        get() = this is ProposalItemKind.SetTagField

    /** True when this kind targets a project rather than a task. */
    private val ProposalItemKind.isProjectBound: Boolean
        get() = this is ProposalItemKind.SetProjectField

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
