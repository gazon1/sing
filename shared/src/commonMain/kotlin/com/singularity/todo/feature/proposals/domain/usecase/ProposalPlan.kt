package com.singularity.todo.feature.proposals.domain.usecase

import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.proposals.domain.model.ProposedTimeEntry
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.Tag
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
    data class SetTaskTags(override val taskId: TaskId, val tagIds: Set<TagId>) : ProposalPlan
    data class AddChecklistItems(override val taskId: TaskId, val texts: List<String>) : ProposalPlan
    data class AddSubtasks(override val taskId: TaskId, val titles: List<String>) : ProposalPlan
    data class AddTimeEntries(override val taskId: TaskId, val entries: List<ProposedTimeEntry>) : ProposalPlan

    /**
     * A fully-resolved note write.
     *
     * The note is the note to write, already built: the planner knows the field the
     * change sets and the note that field belongs to, and the dispatcher writes it.
     * The dispatcher never inspects the value to decide what to write — a value it
     * does not understand would be a write it cannot make.
     */
    data class WriteNote(val note: Note) : ProposalPlan {
        override val taskId: TaskId? get() = null
    }

    /** A fully-resolved project write — the project's fields changed. */
    data class WriteProject(val project: Project) : ProposalPlan {
        override val taskId: TaskId? get() = null
    }

    /**
     * A task that does not exist yet.
     *
     * The id is the one the proposal named when it was made, so the task the user
     * ends up with is the task the proposal talked about.
     */
    data class CreateTask(val task: Task) : ProposalPlan {
        override val taskId: TaskId get() = task.id
    }

    /** A project that does not exist yet. */
    data class CreateProject(val project: Project) : ProposalPlan {
        override val taskId: TaskId? get() = null
    }

    /** A note that does not exist yet. */
    data class CreateNote(val note: Note) : ProposalPlan {
        override val taskId: TaskId? get() = null
    }

    /** A fully-resolved tag write — the tag's fields changed. */
    data class WriteTag(val tag: Tag) : ProposalPlan {
        override val taskId: TaskId? get() = null
    }

    /** A tag that does not exist yet. */
    data class CreateTag(val tag: Tag) : ProposalPlan {
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
