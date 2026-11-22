package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.ai.ToolOutputJson
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.domain.NoteContentMapper
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

/**
 * Which brief to read.
 *
 * Exactly one of [projectId] and [taskId] is given: a brief hangs on one owner, and a
 * call that named both would have no single answer.
 */
@Serializable
data class GetKnowledgeBriefInput(val projectId: String? = null, val taskId: String? = null)

/**
 * The brief, as the model reads it.
 *
 * @property bodyMarkdown the note's body as markdown — the form a model reads. The
 *   note stores canonical HTML, so the read converts rather than handing back markup.
 * @property updatedAt when the note was last written, epoch millis. The caller passes
 *   it back as `expectedUpdatedAt` when it refreshes the brief, so an update written
 *   against a note nobody has touched since is the only one that lands.
 * @property attachedNotesSeen how many attached notes the read looked at — a page, not
 *   a total. The reads are bounded, so a project with more than the page reports the
 *   page size; more than one means [noteId] is the newest of them, not the only one.
 */
@Serializable
data class GetKnowledgeBriefOutput(
    val found: Boolean,
    val noteId: String? = null,
    val title: String? = null,
    val bodyMarkdown: String? = null,
    val updatedAt: Long? = null,
    val attachedNotesSeen: Int = 0,
    /** The owner the brief was read for: `project` or `task`. */
    val owner: String? = null,
    /** Set when there is no brief, or when the call could not be answered at all. */
    val reason: String? = null,
)

/**
 * Reads the knowledge brief of a project or of a task.
 *
 * ## Why this is one tool and not two
 *
 * A brief is a note attached to one owner, and the question is the same either way:
 * *what does the app already know about this?* Two tools would be two names for one
 * question, which is the split `docs/decisions/2026-11-06-one-name-one-question.md`
 * refuses.
 *
 * ## What it reads, and what it refuses
 *
 * The brief is the newest note attached to the owner — the `project_id` or `task_id`
 * column, written once when the note was created, not a link in some other note's
 * body. The owner is resolved first, so a hallucinated id is answered as "not found"
 * rather than as an empty brief: the model has to be able to tell "nobody wrote this
 * yet" from "there is no such project".
 *
 * ## One brief is a convention, not a constraint
 *
 * Nothing stops an owner from accumulating several attached notes, and this read does
 * not pick one by the model's intent: it answers with the newest, and says how many it
 * saw. Making "one brief per owner" a database rule would refuse the second note the
 * user creates by hand, in a list that has no notion of a brief. So the convention is
 * enforced where a brief is *written* — `create_note` attaches a note only when the
 * agent asks for one, and the agent reads first — and a second attached note is a
 * second note, newest first, not a corruption.
 */
class GetKnowledgeBriefTool(
    private val notesRepository: NotesRepository,
    private val projectsRepository: ProjectsRepository,
    private val taskRepository: TaskRepository,
) : SimpleTool<GetKnowledgeBriefInput>(
        TypeToken.of(GetKnowledgeBriefInput::class.java),
        NAME,
        DESCRIPTION,
    ) {
    override suspend fun execute(args: GetKnowledgeBriefInput): String {
        val projectId = args.projectId?.trim()?.takeIf { it.isNotEmpty() }
        val taskId = args.taskId?.trim()?.takeIf { it.isNotEmpty() }

        val output =
            when {
                projectId != null && taskId != null -> {
                    answer(reason = "Pass a projectId or a taskId, not both.")
                }

                projectId != null -> {
                    if (projectsRepository.get(ProjectId.fromString(projectId)) == null) {
                        answer(reason = "There is no project with id \"$projectId\".")
                    } else {
                        fromNotes(
                            owner = "project",
                            notes =
                                notesRepository
                                    .watchAttachedToProject(ProjectId.fromString(projectId), NOTE_LIMIT)
                                    .first(),
                        )
                    }
                }

                taskId != null -> {
                    if (taskRepository.get(TaskId.fromString(taskId)) == null) {
                        answer(reason = "There is no task with id \"$taskId\".")
                    } else {
                        fromNotes(
                            owner = "task",
                            // The kind is part of the query, not a filter over the logbook's
                            // page: filtering after a bounded read loses a brief that sits
                            // behind enough newer daily notes, and reports "none" for a
                            // task that has one.
                            notes =
                                notesRepository
                                    .watchPlainAttachedToTask(TaskId.fromString(taskId), NOTE_LIMIT)
                                    .first(),
                        )
                    }
                }

                else -> {
                    answer(reason = "Pass a projectId or a taskId.")
                }
            }

        return ToolOutputJson.encodeToString(GetKnowledgeBriefOutput.serializer(), output)
    }

    /** The newest attached note as the brief, or a stated "none yet". */
    private fun fromNotes(
        owner: String,
        notes: List<Note>,
    ): GetKnowledgeBriefOutput {
        val newest =
            notes.sortedWith(NOTE_ORDER).firstOrNull()
                ?: return answer(
                    owner = owner,
                    attachedNotesSeen = 0,
                    reason = "No note is attached to this $owner yet, so there is no brief to read.",
                )
        return GetKnowledgeBriefOutput(
            found = true,
            noteId = newest.id.value,
            title = newest.title,
            bodyMarkdown = bodyOf(newest),
            updatedAt = newest.updatedAt.toEpochMilliseconds(),
            attachedNotesSeen = notes.size,
            owner = owner,
        )
    }

    /**
     * The body as markdown.
     *
     * The note stores canonical HTML, and a note created from a proposal has no
     * `bodyMarkdown` at all. A read that handed back that null would tell the model
     * the brief is empty when it is not, so the markdown is derived here.
     */
    private fun bodyOf(note: Note): String? =
        note.bodyMarkdown?.takeIf { it.isNotBlank() }
            ?: note.bodyHtml?.takeIf { it.isNotBlank() }?.let { NoteContentMapper.toMarkdown(it) }

    private fun answer(
        owner: String? = null,
        attachedNotesSeen: Int = 0,
        reason: String,
    ): GetKnowledgeBriefOutput =
        GetKnowledgeBriefOutput(
            found = false,
            attachedNotesSeen = attachedNotesSeen,
            owner = owner,
            reason = reason,
        )

    companion object {
        const val NAME = "get_knowledge_brief"

        /**
         * How many attached notes a read looks through.
         *
         * A brief is one note; the bound is what keeps a project that accumulated
         * dozens of attached notes from making every read un bounded.
         */
        private const val NOTE_LIMIT = 20

        /**
         * Newest first, then by ascending id — exactly the `ORDER BY created_at DESC,
         * id ASC` both reads run.
         *
         * The direction of the tie-break is not cosmetic: a comparator that sorted the
         * id the other way picked the opposite note on a same-second tie, so the tool
         * answered with a note the store does not call first.
         */
        private val NOTE_ORDER: Comparator<Note> =
            compareByDescending<Note> { it.createdAt }.thenBy { it.id.value }

        /**
         * The description the model reads.
         *
         * It says what a brief *is* — a note attached to the project or task — so the
         * model asks for this tool rather than searching for a note that mentions it.
         */
        const val DESCRIPTION =
            "Read the knowledge brief of a project or a task: the note attached to it, as " +
                "markdown, with the updatedAt to pass back as expectedUpdatedAt when refreshing it. " +
                "Pass exactly one of projectId or taskId. Use it before proposing an update, so " +
                "the change is written against what the app already says."
    }
}
