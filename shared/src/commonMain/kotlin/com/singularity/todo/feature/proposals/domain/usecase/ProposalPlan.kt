package com.singularity.todo.feature.proposals.domain.usecase

import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.proposals.domain.model.NoteField
import com.singularity.todo.feature.proposals.domain.model.ProposedTimeEntry
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * A validated, ready-to-apply change, produced by [ProposalPlanner] and consumed
 * by [ProposalDispatch].
 *
 * Every parse and precondition check happens in [ProposalPlanner], so between the
 * claim and the dispatch there is nothing left that can fail on a parse.
 * Adding a new [ProposalPlan] variant makes the compiler point at both
 * [ProposalPlanner.planTaskItem] and [ProposalDispatch.dispatch].
 */
sealed interface ProposalPlan {
    val taskId: TaskId?

    /** A fully-resolved task write. Every parse already happened in `ProposalPlanner`. */
    data class WriteTask(val task: Task) : ProposalPlan {
        override val taskId: TaskId get() = task.id
    }

    data class AddTags(override val taskId: TaskId, val tagIds: Set<TagId>) : ProposalPlan
    data class RemoveTags(override val taskId: TaskId, val tagIds: Set<TagId>) : ProposalPlan
    data class AddChecklistItems(override val taskId: TaskId, val texts: List<String>) : ProposalPlan
    data class AddSubtasks(override val taskId: TaskId, val titles: List<String>) : ProposalPlan
    data class AddTimeEntries(override val taskId: TaskId, val entries: List<ProposedTimeEntry>) : ProposalPlan

    /** A fully-resolved note write. */
    data class WriteNote(val note: Note, val field: NoteField, val value: String) : ProposalPlan {
        override val taskId: TaskId? get() = null
    }

    data class DeleteNote(val noteId: NoteId, val reason: String?) : ProposalPlan {
        override val taskId: TaskId? get() = null
    }

    data class ExtractNoteActions(val noteId: NoteId, val actions: List<String>) : ProposalPlan {
        override val taskId: TaskId? get() = null
    }

    /** Soft-delete (archive) a task. */
    data class DeleteTask(val delTaskId: TaskId, val reason: String?) : ProposalPlan {
        override val taskId: TaskId? get() = null
    }

    /** Soft-delete (archive) a project. */
    data class DeleteProject(val projectId: ProjectId, val reason: String?) : ProposalPlan {
        override val taskId: TaskId? get() = null
    }

    /** Delete a tag. */
    data class DeleteTag(val tagId: TagId, val reason: String?) : ProposalPlan {
        override val taskId: TaskId? get() = null
    }
}
