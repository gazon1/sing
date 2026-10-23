package com.singularity.todo.feature.proposals.domain.usecase

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.proposals.domain.model.CreationBoundKind
import com.singularity.todo.feature.proposals.domain.model.NoteBoundKind
import com.singularity.todo.feature.proposals.domain.model.NoteValue
import com.singularity.todo.feature.proposals.domain.model.ProjectBoundKind
import com.singularity.todo.feature.proposals.domain.model.ProjectValue
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.TagBoundKind
import com.singularity.todo.feature.proposals.domain.model.TagValue
import com.singularity.todo.feature.proposals.domain.model.TaskBoundKind
import com.singularity.todo.feature.proposals.domain.model.TaskValue
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlin.time.Clock

/**
 * Turns a stored [ProposalItem] into a validated, ready-to-apply [ProposalPlan].
 *
 * All validation lives here so that [ProposalDispatch] cannot fail on a parse: between
 * the claim and the dispatch there is nothing left that can throw.
 *
 * **Pure:** this class holds no state and no repository references. The caller fetches
 * all required entities before calling the planner, so this class has no I/O of its own.
 * This makes it fully testable without fakes or test repositories.
 *
 * @param clock For timestamp generation in subtask creation and in the entities a
 *   creation proposal brings into being.
 */
class ProposalPlanner(private val clock: Clock) {

    /**
     * Plans a change about a note.
     *
     * @param item The stored proposal item.
     * @param kind The change itself — the group says which note it is about.
     * @param note The note fetched by the caller. Pass `null` if the note does not exist,
     *             in which case this function throws — for the changes that need it.
     */
    fun planNoteItem(item: ProposalItem, kind: NoteBoundKind, note: Note?): ProposalPlan = when (kind) {
        is ProposalItemKind.SetNoteField -> {
            // The same refusal the other dictionaries make, and for the same
            // reason: a value that does not read is not a change that can be
            // applied, and the plan is built before the claim. It comes before
            // the note lookup, because the reason the value does not read is
            // the one the user has to hear — not that the note is missing.
            kind.value.readFailure?.let { reason -> error(reason) }
            val n = note ?: error("Note ${item.targetId} not found")
            ProposalPlan.WriteNote(withNoteValue(n, kind.value))
        }

        is ProposalItemKind.DeleteNote -> {
            val noteId = NoteId(item.targetId)
            ProposalPlan.DeleteNote(noteId, kind.reason)
        }

        is ProposalItemKind.ExtractActions -> {
            require(kind.actions.isNotEmpty()) { "ExtractActions is empty" }
            ProposalPlan.ExtractNoteActions(NoteId(item.targetId), kind.actions)
        }
    }

    /**
     * Plans a change about a project.
     *
     * @param project The project fetched by the caller. Pass `null` if the project does
     *   not exist, in which case this function throws — for the changes that need it.
     */
    fun planProjectItem(item: ProposalItem, kind: ProjectBoundKind, project: Project?): ProposalPlan = when (kind) {
        is ProposalItemKind.SetProjectField -> {
            val p = project ?: error("Project ${item.targetId} not found")
            ProposalPlan.WriteProject(withProjectValue(p, kind.value))
        }

        is ProposalItemKind.DeleteProject -> {
            val projectId = ProjectId.fromString(item.targetId)
            ProposalPlan.DeleteProject(projectId, kind.reason)
        }
    }

    /**
     * Plans a change about a tag.
     *
     * @param tag The tag fetched by the caller. Pass `null` if the tag does not exist,
     *   in which case this function throws — for the changes that need it.
     */
    fun planTagItem(item: ProposalItem, kind: TagBoundKind, tag: Tag?): ProposalPlan = when (kind) {
        is ProposalItemKind.SetTagField -> {
            val t = tag ?: error("Tag ${item.targetId} not found")
            ProposalPlan.WriteTag(withTagValue(t, kind.value))
        }

        is ProposalItemKind.DeleteTag -> {
            val tagId = TagId.fromString(item.targetId)
            ProposalPlan.DeleteTag(tagId, kind.reason)
        }
    }

    /**
     * Plans a creation.
     *
     * The thing the proposal creates does not exist yet: the proposal named the id it
     * *will* have, and the id is the item's target. So there is nothing to fetch, and
     * everything to build.
     *
     * @param userId The owner of the entity that will come into being.
     */
    fun planCreateItem(item: ProposalItem, kind: CreationBoundKind, userId: UserId): ProposalPlan = when (kind) {
        is ProposalItemKind.CreateTask -> ProposalPlan.CreateTask(buildTask(kind, item.targetId, userId))
        is ProposalItemKind.CreateProject -> ProposalPlan.CreateProject(buildProject(kind, item.targetId, userId))
        is ProposalItemKind.CreateNote -> ProposalPlan.CreateNote(buildNote(kind, item.targetId, userId))
        is ProposalItemKind.CreateTag -> ProposalPlan.CreateTag(buildTag(kind, item.targetId, userId))
    }

    /**
     * Plans a change about a task.
     *
     * The change is what says it is about a task — the caller does not decide that,
     * and cannot get it wrong. So this function switches over the task group
     * exhaustively: a change that joins the group and is not handled here does not
     * compile.
     *
     * @param item The stored proposal item.
     * @param kind The change itself.
     * @param task The task fetched by the caller. Pass `null` if the task does not exist,
     *   in which case this function throws — for the changes that need it.
     * @param resolvedTagIds For [ProposalItemKind.AddTags]: the ids of the tags the
     *   change names, resolved by the caller.
     */
    fun planTaskItem(
        item: ProposalItem,
        kind: TaskBoundKind,
        task: Task?,
        resolvedTagIds: Set<TagId> = emptySet(),
    ): ProposalPlan {
        val taskId = TaskId(item.targetId.ifBlank { task?.id?.value ?: error("No task id") })
        return when (kind) {
            is ProposalItemKind.SetTaskField -> {
                // Validation here, not in dispatch: a value the task cannot hold must fail
                // *before* the claim, or the item burns its one decision on a write that throws.
                val t = task ?: error("Task ${item.targetId} not found")
                ProposalPlan.WriteTask(withTaskValue(t, kind.value))
            }

            is ProposalItemKind.SetTaskTags -> {
                ProposalPlan.SetTaskTags(taskId, kind.tagIds.map(TagId::fromString).toSet())
            }

            is ProposalItemKind.AddTags -> {
                require(resolvedTagIds.isNotEmpty()) { "The tags this change names could not be resolved" }
                ProposalPlan.AddTags(taskId, resolvedTagIds)
            }

            is ProposalItemKind.RemoveTags -> {
                val ids = kind.tagIds.map(TagId::fromString).toSet()
                require(ids.isNotEmpty()) { "RemoveTags has no tag ids" }
                ProposalPlan.RemoveTags(taskId, ids)
            }

            is ProposalItemKind.AddChecklistItems -> {
                val texts = kind.texts.cleaned()
                require(texts.isNotEmpty()) { "AddChecklistItems is empty after cleaning" }
                ProposalPlan.AddChecklistItems(taskId, texts)
            }

            is ProposalItemKind.AddSubtasks -> {
                val titles = kind.titles.cleaned()
                require(titles.isNotEmpty()) { "AddSubtasks is empty after cleaning" }
                ProposalPlan.AddSubtasks(taskId, titles)
            }

            is ProposalItemKind.AddTimeEntries -> {
                kind.entries.forEach { entry ->
                    require(entry.endedAt > entry.startedAt) {
                        "Proposed time entry ends (${entry.endedAt}) before it starts (${entry.startedAt})"
                    }
                }
                require(kind.entries.isNotEmpty()) { "AddTimeEntries is empty" }
                ProposalPlan.AddTimeEntries(taskId, kind.entries)
            }

            is ProposalItemKind.DeleteTask -> {
                ProposalPlan.DeleteTask(delTaskId = taskId, reason = kind.reason)
            }
        }
    }

    /**
     * Returns [task] with [value] applied, or throws if the value is not something
     * the task can hold.
     *
     * The value arrives typed: a priority is a [TaskPriority], a date is a
     * [LocalDate]. So there is nothing to parse here, and the two places that used
     * to disagree about the spelling of a value — the tool that proposed it and the
     * planner that applied it — cannot disagree any more.
     *
     * Pure: reads nothing and writes nothing, so every rejection reason is
     * reachable from a unit test without a repository.
     */
    fun withTaskValue(task: Task, value: TaskValue): Task = when (value) {
        is TaskValue.Title -> {
            val title = value.value.trim()
            require(title.isNotEmpty()) { "Proposed title is blank" }
            task.copy(title = title)
        }

        is TaskValue.Description -> task.copy(description = value.value.trim().takeIf(String::isNotEmpty))

        is TaskValue.Priority -> task.copy(priority = value.value)

        is TaskValue.DueDate -> task.copy(dueDate = value.value)

        is TaskValue.Status -> {
            task.copy(completedAt = if (value.value == TaskStatus.Completed) clock.now() else null)
        }

        is TaskValue.EstimateMinutes -> {
            require(value.value >= 0) { "Estimate cannot be negative" }
            task.copy(estimateMinutes = value.value)
        }

        is TaskValue.Kind -> task.copy(kind = value.value)

        is TaskValue.DueTime -> task.copy(dueTime = value.value)

        is TaskValue.Project -> {
            val projectId = value.value.trim().takeIf { it.isNotEmpty() }
            task.copy(projectId = projectId?.let { ProjectId.fromString(it) })
        }

        is TaskValue.Someday -> task.copy(someday = value.value)

        // A value this build cannot read. The change stays on the card with the
        // reason it cannot be applied, and the user can type a value that does
        // read. It cannot be applied as it stands — and saying so is the point:
        // the plan is built before the claim, so the change is neither decided
        // nor lost.
        is TaskValue.Unreadable -> error(value.readFailure ?: "This value cannot be read.")
    }

    /**
     * Returns [note] with [value] applied, or throws if the value is not something
     * the note can hold.
     *
     * The note to write is built here, where the field is known: the dispatcher
     * writes a note, it does not decide which field of it to write.
     */
    fun withNoteValue(note: Note, value: NoteValue): Note = when (value) {
        is NoteValue.Title -> note.copy(title = value.value.trim())

        is NoteValue.Body -> note.copy(bodyHtml = value.value)

        is NoteValue.Summary -> note.copy(bodyHtml = value.value)

        // A value this build cannot read. The change stays on the card with the
        // reason it cannot be applied, and the user can type a value that does
        // read.
        is NoteValue.Unreadable -> error(value.readFailure ?: "This value cannot be read.")
    }

    /**
     * Returns [project] with [value] applied, or throws if the value is not something
     * the project can hold.
     */
    fun withProjectValue(project: Project, value: ProjectValue): Project = when (value) {
        is ProjectValue.Name -> {
            val name = value.value.trim()
            require(name.isNotEmpty()) { "Proposed project name is blank" }
            project.copy(name = name)
        }

        is ProjectValue.Description -> project.copy(description = value.value.trim().takeIf(String::isNotEmpty))

        is ProjectValue.Color -> project.copy(color = value.value)

        is ProjectValue.Icon -> project.copy(icon = value.value.trim().takeIf(String::isNotEmpty))

        // A value this build cannot read. The change stays on the card with the
        // reason it cannot be applied, and the user can type a value that does
        // read. It cannot be applied as it stands — and saying so is the point:
        // the plan is built before the claim, so the change is neither decided
        // nor lost.
        is ProjectValue.Unreadable -> error(value.readFailure ?: "This value cannot be read.")
    }

    /**
     * Returns [tag] with [value] applied, or throws if the value is not something
     * the tag can hold.
     */
    fun withTagValue(tag: Tag, value: TagValue): Tag = when (value) {
        is TagValue.Name -> {
            val name = value.value.trim()
            require(name.isNotEmpty()) { "Proposed tag name is blank" }
            tag.copy(name = name)
        }

        is TagValue.Color -> tag.copy(color = value.value)

        // A value this build cannot read. The change stays on the card with the
        // reason it cannot be applied, and the user can type a value that does
        // read. It cannot be applied as it stands — and saying so is the point:
        // the plan is built before the claim, so the change is neither decided
        // nor lost.
        is TagValue.Unreadable -> error(value.readFailure ?: "This value cannot be read.")
    }

    // ── The creations ────────────────────────────────────────────────────────────

    /** The task a [ProposalItemKind.CreateTask] item brings into being. */
    private fun buildTask(
        kind: ProposalItemKind.CreateTask,
        targetId: String,
        userId: UserId,
    ): Task {
        val proposed = kind.task
        val title = proposed.title.trim()
        require(title.isNotEmpty()) { "Proposed task title is blank" }
        val now = clock.now()
        return Task(
            id = TaskId(targetId),
            title = title,
            description = proposed.description?.trim()?.takeIf(String::isNotEmpty),
            priority = proposed.priority,
            kind = proposed.kind,
            projectId = proposed.projectId?.takeIf { it.isNotBlank() }?.let { ProjectId.fromString(it) },
            parentTaskId = proposed.parentTaskId?.takeIf { it.isNotBlank() }?.let { TaskId.fromString(it) },
            tags = proposed.tagIds.map { TagId.fromString(it) },
            dueDate = proposed.dueDate,
            dueTime = proposed.dueTime,
            someday = proposed.someday,
            createdAt = now,
            updatedAt = now,
            userId = userId,
        )
    }

    /** The project a [ProposalItemKind.CreateProject] item brings into being. */
    private fun buildProject(
        kind: ProposalItemKind.CreateProject,
        targetId: String,
        userId: UserId,
    ): Project {
        val proposed = kind.project
        val name = proposed.name.trim()
        require(name.isNotEmpty()) { "Proposed project name is blank" }
        val now = clock.now()
        return Project(
            id = ProjectId.fromString(targetId),
            name = name,
            color = proposed.color,
            icon = proposed.icon?.trim()?.takeIf(String::isNotEmpty),
            description = proposed.description?.trim()?.takeIf(String::isNotEmpty),
            createdAt = now,
            updatedAt = now,
            userId = userId,
        )
    }

    /** The note a [ProposalItemKind.CreateNote] item brings into being. */
    private fun buildNote(
        kind: ProposalItemKind.CreateNote,
        targetId: String,
        userId: UserId,
    ): Note {
        val proposed = kind.note
        val now = clock.now()
        return Note(
            id = NoteId(targetId),
            title = proposed.title.trim(),
            bodyMarkdown = null,
            bodyHtml = proposed.bodyHtml,
            isFolder = proposed.isFolder,
            parentNoteId = proposed.parentNoteId?.takeIf { it.isNotBlank() }?.let { NoteId.fromString(it) },
            createdAt = now,
            updatedAt = now,
            userId = userId,
        )
    }

    /** The tag a [ProposalItemKind.CreateTag] item brings into being. */
    private fun buildTag(
        kind: ProposalItemKind.CreateTag,
        targetId: String,
        userId: UserId,
    ): Tag {
        val proposed = kind.tag
        val name = proposed.name.trim()
        require(name.isNotEmpty()) { "Proposed tag name is blank" }
        val now = clock.now()
        return Tag(
            id = TagId.fromString(targetId),
            name = name,
            color = proposed.color,
            createdAt = now,
            updatedAt = now,
            userId = userId,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun List<String>.cleaned(): List<String> = map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
