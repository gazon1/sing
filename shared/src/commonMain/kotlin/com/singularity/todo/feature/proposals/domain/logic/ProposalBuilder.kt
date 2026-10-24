package com.singularity.todo.feature.proposals.domain.logic

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.model.ProposalTargetKind
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlin.time.Clock

/**
 * Factory for building [AiProposal] + [ProposalItem] pairs.
 *
 * Reduces duplication between [TaskAiSlot] and note/proposal UI surfaces.
 * All proposal-creating code shares the same construction logic, so changes to the
 * entity shape (e.g. adding `sortOrder` or changing `fingerprint`) are made in one place.
 *
 * ## Why the target is not written down here
 *
 * The builder used to take the kind of target as a second declaration, beside the
 * change it was about — and a caller that got the two apart wrote a proposal the
 * confirmation screen would never find. The change says what it is about
 * ([ProposalItemKind.target]), so the builder reads it from there: there is nothing
 * left to type, and nothing left to get wrong.
 */
object ProposalBuilder {

    /**
     * Builds a single-item proposal for a task-bound action.
     *
     * @param kind The action kind (e.g. [ProposalItemKind.SetTaskField]).
     * @param taskId The target task.
     * @param userId Proposal owner.
     * @param source Which surface triggered this proposal.
     * @param clock For timestamps.
     */
    fun forTask(
        kind: ProposalItemKind,
        taskId: TaskId,
        userId: UserId,
        source: ProposalSource,
        clock: Clock,
    ): Pair<AiProposal, List<ProposalItem>> {
        requireTarget(kind, ProposalTargetKind.Task)
        return forTarget(
            kind = kind,
            targetId = taskId.value,
            userId = userId,
            source = source,
            clock = clock,
        )
    }

    /**
     * Builds a single-item proposal for a note-bound action.
     *
     * @param kind The action kind (e.g. [ProposalItemKind.SetNoteField]).
     * @param noteId The target note id string.
     * @param userId Proposal owner.
     * @param source Which surface triggered this proposal.
     * @param clock For timestamps.
     */
    fun forNote(
        kind: ProposalItemKind,
        noteId: String,
        userId: UserId,
        source: ProposalSource,
        clock: Clock,
    ): Pair<AiProposal, List<ProposalItem>> {
        requireTarget(kind, ProposalTargetKind.Note)
        return forTarget(
            kind = kind,
            targetId = noteId,
            userId = userId,
            source = source,
            clock = clock,
        )
    }

    /**
     * Builds a single-item proposal against any kind of target.
     *
     * The general form, for the targets that are neither a task nor a note — a
     * project, a tag. The change says which one it is, so the caller does not.
     *
     * @param targetId the target's id, in string form.
     */
    fun forTarget(
        kind: ProposalItemKind,
        targetId: String,
        userId: UserId,
        source: ProposalSource,
        clock: Clock,
    ): Pair<AiProposal, List<ProposalItem>> {
        val proposalId = ProposalId.generate()
        val itemId = ProposalItemId.generate()
        val now = clock.now()
        val item = newItem(kind, targetId, itemId, proposalId)
        val proposal = AiProposal(
            id = proposalId,
            targetKind = kind.target.targetKind,
            targetId = targetId,
            userId = userId,
            source = source,
            createdAt = now,
            updatedAt = now,
            items = listOf(item),
        )
        return proposal to listOf(item)
    }

    /**
     * Builds a multi-item proposal (e.g. for [ProposalItemKind.AddSubtasks]).
     */
    fun multiForTask(
        items: List<ProposalItem>,
        taskId: TaskId,
        userId: UserId,
        source: ProposalSource,
        clock: Clock,
    ): Pair<AiProposal, List<ProposalItem>> {
        requireTarget(items, ProposalTargetKind.Task)
        return multiForTarget(
            items = items,
            targetId = taskId.value,
            userId = userId,
            source = source,
            clock = clock,
        )
    }

    /**
     * Builds a multi-item proposal against any kind of target.
     *
     * The general form of [multiForTask]: a change that touches a project, a note
     * or a tag has more than one thing to say, and a proposal holds them together
     * so the user decides about the whole change at once.
     *
     * The changes say what they are about, and a proposal holds changes about one
     * thing — so the kind of target is read from them, and a batch that mixes
     * kinds fails loudly instead of being addressed to the wrong thing.
     */
    fun multiForTarget(
        items: List<ProposalItem>,
        targetId: String,
        userId: UserId,
        source: ProposalSource,
        clock: Clock,
    ): Pair<AiProposal, List<ProposalItem>> {
        val proposalId = ProposalId.generate()
        val now = clock.now()
        val updatedItems = items.map { it.copy(proposalId = proposalId) }
        val proposal = AiProposal(
            id = proposalId,
            targetKind = targetKindOf(updatedItems),
            targetId = targetId,
            userId = userId,
            source = source,
            createdAt = now,
            updatedAt = now,
            items = updatedItems,
        )
        return proposal to updatedItems
    }

    /**
     * One proposal item, with the summary the user reads and the fingerprint the
     * machinery compares.
     *
     * A caller that builds items by hand has to remember both, and a caller that
     * remembers one of them gets a card that says nothing or a rejection that does
     * not stick. So the item is built here.
     */
    fun item(kind: ProposalItemKind, targetId: String): ProposalItem = newItem(
        kind = kind,
        targetId = targetId,
        itemId = ProposalItemId.generate(),
        proposalId = ProposalId.generate(),
    )

    /**
     * Refuses a change that is about something else than the id it was proposed
     * against.
     *
     * The two are separate facts: the change says what it is about, and the id
     * says which one. A caller that mixes them would write a proposal addressed to
     * a task that holds a note's change — a proposal the confirmation screen does
     * not show, and the user never sees.
     */
    private fun requireTarget(kind: ProposalItemKind, expected: ProposalTargetKind) {
        val declared = kind.target.targetKind
        require(declared == expected) {
            "The change is about ${declared.storageValue}, but it was proposed against a " +
                "${expected.storageValue} id."
        }
    }

    /**
     * The kind of target a batch of changes is about.
     *
     * A proposal holds changes about one thing. A batch that mixes kinds is not a
     * proposal about anything, and it is refused rather than addressed to whichever
     * change happened to come first.
     */
    private fun requireTarget(items: List<ProposalItem>, expected: ProposalTargetKind) {
        val declared = targetKindOf(items)
        require(declared == expected) {
            "The changes are about ${declared.storageValue}, but they were proposed against a " +
                "${expected.storageValue} id."
        }
    }

    /** The one kind of target the changes in a batch are about. */
    private fun targetKindOf(items: List<ProposalItem>): ProposalTargetKind =
        ProposalTargetKind.of(items.map { it.kind })

    @Suppress("FunctionExpressionBody")
    private fun newItem(
        kind: ProposalItemKind,
        targetId: String,
        itemId: ProposalItemId,
        proposalId: ProposalId,
    ): ProposalItem = ProposalItem.pending(
        id = itemId,
        proposalId = proposalId,
        kind = kind,
        targetId = targetId,
        humanSummary = kind.humanSummary,
        fingerprint = ProposalFingerprint.of(kind, targetId),
    )
}
