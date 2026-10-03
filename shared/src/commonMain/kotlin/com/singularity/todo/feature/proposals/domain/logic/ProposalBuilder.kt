@file:Suppress("BlankLineBetweenWhenConditions")

package com.singularity.todo.feature.proposals.domain.logic

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlin.time.Clock

/**
 * Factory for building [AiProposal] + [ProposalItem] pairs.
 *
 * Reduces duplication between [TaskAiSlot] and note/proposal UI surfaces.
 * All proposal-creating code shares the same construction logic, so changes to the
 * entity shape (e.g. adding `sortOrder` or changing `fingerprint`) are made in one place.
 */
object ProposalBuilder {

    /**
     * Builds a single-item proposal for a task-bound action.
     *
     * @param kind The action kind (e.g. [ProposalItemKind.SetTaskField]).
     * @param taskId The target task.
     * @param targetId String form of [taskId] (used in [ProposalItem.targetId]).
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
        val proposalId = ProposalId.generate()
        val itemId = ProposalItemId.generate()
        val now = clock.now()
        val item = newItem(kind, taskId.value, itemId, proposalId)
        val proposal = AiProposal(
            id = proposalId,
            targetKind = AiProposal.TARGET_KIND_TASK,
            targetId = taskId.value,
            userId = userId,
            source = source,
            status = ProposalStatus.Pending,
            createdAt = now,
            updatedAt = now,
            items = listOf(item),
        )
        return proposal to listOf(item)
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
        val proposalId = ProposalId.generate()
        val itemId = ProposalItemId.generate()
        val now = clock.now()
        val item = newItem(kind, noteId, itemId, proposalId)
        val proposal = AiProposal(
            id = proposalId,
            targetKind = AiProposal.TARGET_KIND_NOTE,
            targetId = noteId,
            userId = userId,
            source = source,
            status = ProposalStatus.Pending,
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
        val proposalId = ProposalId.generate()
        val now = clock.now()
        val updatedItems = items.map { it.copy(proposalId = proposalId) }
        val proposal = AiProposal(
            id = proposalId,
            targetKind = AiProposal.TARGET_KIND_TASK,
            targetId = taskId.value,
            userId = userId,
            source = source,
            status = ProposalStatus.Pending,
            createdAt = now,
            updatedAt = now,
            items = updatedItems,
        )
        return proposal to updatedItems
    }

    @Suppress("FunctionExpressionBody")
    private fun newItem(
        kind: ProposalItemKind,
        targetId: String,
        itemId: ProposalItemId,
        proposalId: ProposalId,
    ): ProposalItem = ProposalItem(
        id = itemId,
        proposalId = proposalId,
        kind = kind,
        targetId = targetId,
        humanSummary = kind.humanSummary,
        status = ProposalItemStatus.Pending,
        fingerprint = ProposalFingerprint.of(kind, targetId),
        sortOrder = 0,
    )
}

/** Human-readable summary derived from a [ProposalItemKind]. */
private val ProposalItemKind.humanSummary: String
    get() = when (this) {
        is ProposalItemKind.SetTaskField -> when (field) {
            com.singularity.todo.feature.proposals.domain.model.TaskField.Title -> "Update title"

            com.singularity.todo.feature.proposals.domain.model.TaskField.Description -> "Update description"

            com.singularity.todo.feature.proposals.domain.model.TaskField.Priority -> "Set priority"

            com.singularity.todo.feature.proposals.domain.model.TaskField.DueDate -> "Set due date"

            com.singularity.todo.feature.proposals.domain.model.TaskField.EstimateMinutes -> "Set estimate"

            com.singularity.todo.feature.proposals.domain.model.TaskField.Status -> "Set status"
        }
        is ProposalItemKind.AddTags -> "Add tags"

        is ProposalItemKind.RemoveTags -> "Remove tags"

        is ProposalItemKind.AddChecklistItems -> "${texts.size} checklist item(s)"

        is ProposalItemKind.AddSubtasks -> "${titles.size} subtask(s)"

        is ProposalItemKind.AddTimeEntries -> "Add time entry"

        is ProposalItemKind.SetNoteField -> when (field) {
            com.singularity.todo.feature.proposals.domain.model.NoteField.Title -> "Update note title"

            com.singularity.todo.feature.proposals.domain.model.NoteField.Body -> "Update note body"

            com.singularity.todo.feature.proposals.domain.model.NoteField.Summary -> "Summarize note"
        }
        is ProposalItemKind.DeleteNote -> "Delete note"
        is ProposalItemKind.ExtractActions -> "${actions.size} action(s) from note"
        is ProposalItemKind.DeleteTask -> "Delete task"
        is ProposalItemKind.DeleteProject -> "Delete project"
        is ProposalItemKind.DeleteTag -> "Delete tag"
    }
