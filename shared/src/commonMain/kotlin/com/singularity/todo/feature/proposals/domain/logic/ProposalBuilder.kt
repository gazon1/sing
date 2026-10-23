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
    ): Pair<AiProposal, List<ProposalItem>> = forTarget(
        kind = kind,
        targetKind = AiProposal.TARGET_KIND_TASK,
        targetId = taskId.value,
        userId = userId,
        source = source,
        clock = clock,
    )

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
    ): Pair<AiProposal, List<ProposalItem>> = forTarget(
        kind = kind,
        targetKind = AiProposal.TARGET_KIND_NOTE,
        targetId = noteId,
        userId = userId,
        source = source,
        clock = clock,
    )

    /**
     * Builds a single-item proposal against any kind of target.
     *
     * The general form, for the targets that are neither a task nor a note — a
     * project, a tag. The specialised forms above say the target kind for the two
     * common cases, because a caller that gets the kind wrong writes a proposal the
     * confirmation screen will not find.
     *
     * @param targetKind one of the `AiProposal.TARGET_KIND_*` constants.
     * @param targetId the target's id, in string form.
     */
    fun forTarget(
        kind: ProposalItemKind,
        targetKind: String,
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
            targetKind = targetKind,
            targetId = targetId,
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
    ): Pair<AiProposal, List<ProposalItem>> = multiForTarget(
        items = items,
        targetKind = AiProposal.TARGET_KIND_TASK,
        targetId = taskId.value,
        userId = userId,
        source = source,
        clock = clock,
    )

    /**
     * Builds a multi-item proposal against any kind of target.
     *
     * The general form of [multiForTask]: a change that touches a project, a note
     * or a tag has more than one thing to say, and a proposal holds them together
     * so the user decides about the whole change at once.
     */
    fun multiForTarget(
        items: List<ProposalItem>,
        targetKind: String,
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
            targetKind = targetKind,
            targetId = targetId,
            userId = userId,
            source = source,
            status = ProposalStatus.Pending,
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

/**
 * Human-readable summary derived from a [ProposalItemKind].
 *
 * The line the user reads in the confirmation screen, so it says what will happen
 * rather than which variant of the domain type was chosen.
 */
val ProposalItemKind.humanSummary: String
    get() = when (this) {
        is ProposalItemKind.SetTaskField -> when (field) {
            com.singularity.todo.feature.proposals.domain.model.TaskField.Title -> "Update title"

            com.singularity.todo.feature.proposals.domain.model.TaskField.Description -> "Update description"

            com.singularity.todo.feature.proposals.domain.model.TaskField.Priority -> "Set priority"

            com.singularity.todo.feature.proposals.domain.model.TaskField.DueDate -> "Set due date"

            com.singularity.todo.feature.proposals.domain.model.TaskField.EstimateMinutes -> "Set estimate"

            com.singularity.todo.feature.proposals.domain.model.TaskField.Status -> "Set status"

            com.singularity.todo.feature.proposals.domain.model.TaskField.Kind -> "Set the kind of work"

            com.singularity.todo.feature.proposals.domain.model.TaskField.DueTime -> "Set due time"

            com.singularity.todo.feature.proposals.domain.model.TaskField.Project -> "Change the project"

            com.singularity.todo.feature.proposals.domain.model.TaskField.Someday ->
                if (value.trim().equals("true", ignoreCase = true)) {
                    "Defer the task"
                } else {
                    "Bring the task back"
                }
        }
        is ProposalItemKind.SetTaskTags -> "Set the tags"

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
        is ProposalItemKind.SetProjectField -> when (field) {
            com.singularity.todo.feature.proposals.domain.model.ProjectField.Name -> "Rename the project"

            com.singularity.todo.feature.proposals.domain.model.ProjectField.Description ->
                "Change the project description"

            com.singularity.todo.feature.proposals.domain.model.ProjectField.Color -> "Recolor the project"

            com.singularity.todo.feature.proposals.domain.model.ProjectField.Icon -> "Change the project icon"
        }
        is ProposalItemKind.CreateTask -> "Create a task"
        is ProposalItemKind.CreateProject -> "Create a project"
        is ProposalItemKind.CreateNote -> "Create a note"
        is ProposalItemKind.DeleteNote -> "Delete note"
        is ProposalItemKind.ExtractActions -> "${actions.size} action(s) from note"
        is ProposalItemKind.DeleteTask -> "Delete task"
        is ProposalItemKind.DeleteProject -> "Delete project"
        is ProposalItemKind.DeleteTag -> "Delete tag"
    }
