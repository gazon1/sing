package com.singularity.todo.feature.proposals.domain.usecase

import com.singularity.todo.core.database.LocalTimeFormats
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProjectField
import com.singularity.todo.feature.proposals.domain.model.TaskField
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlinx.datetime.LocalDate
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
     * Plans a note-bound item (no task required).
     *
     * @param item The stored proposal item.
     * @param note The note fetched by the caller. Pass `null` if the note does not exist,
     *             in which case this function throws.
     */
    fun planNoteItem(item: ProposalItem, note: Note?): ProposalPlan = when (val kind = item.kind) {
        is ProposalItemKind.SetNoteField -> {
            val n = note ?: error("Note ${item.targetId} not found")
            ProposalPlan.WriteNote(n, kind.field, kind.value)
        }

        is ProposalItemKind.DeleteNote -> {
            val noteId = NoteId(item.targetId)
            ProposalPlan.DeleteNote(noteId, kind.reason)
        }

        is ProposalItemKind.ExtractActions -> {
            require(kind.actions.isNotEmpty()) { "ExtractActions is empty" }
            ProposalPlan.ExtractNoteActions(NoteId(item.targetId), kind.actions)
        }

        else -> error("Expected note-bound kind but got $kind")
    }

    /**
     * Plans a delete-variant item (no task or note needed — just the target id).
     */
    fun planDeleteItem(item: ProposalItem): ProposalPlan = when (val kind = item.kind) {
        is ProposalItemKind.DeleteTask -> {
            val taskId = TaskId(item.targetId)
            ProposalPlan.DeleteTask(delTaskId = taskId, reason = kind.reason)
        }

        is ProposalItemKind.DeleteProject -> {
            val projectId = ProjectId.fromString(item.targetId)
            ProposalPlan.DeleteProject(projectId, kind.reason)
        }

        is ProposalItemKind.DeleteTag -> {
            val tagId = TagId.fromString(item.targetId)
            ProposalPlan.DeleteTag(tagId, kind.reason)
        }

        else -> error("Expected delete variant but got $kind")
    }

    /**
     * Plans a project-bound item.
     *
     * @param project The project fetched by the caller. Pass `null` if the project does
     *   not exist, in which case this function throws.
     */
    fun planProjectItem(item: ProposalItem, project: Project?): ProposalPlan = when (val kind = item.kind) {
        is ProposalItemKind.SetProjectField -> {
            val p = project ?: error("Project ${item.targetId} not found")
            ProposalPlan.WriteProject(withProjectField(p, kind.field, kind.value))
        }

        else -> error("Expected project-bound kind but got $kind")
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
    fun planCreateItem(item: ProposalItem, userId: UserId): ProposalPlan = when (val kind = item.kind) {
        is ProposalItemKind.CreateTask -> ProposalPlan.CreateTask(buildTask(kind, item.targetId, userId))
        is ProposalItemKind.CreateProject -> ProposalPlan.CreateProject(buildProject(kind, item.targetId, userId))
        is ProposalItemKind.CreateNote -> ProposalPlan.CreateNote(buildNote(kind, item.targetId, userId))
        else -> error("Expected a creation but got $kind")
    }

    /**
     * Plans a task-bound item.
     *
     * @param item The stored proposal item.
     * @param task The task fetched by the caller. Pass `null` if the task does not exist,
     *             in which case this function throws.
     * @param resolvedTagIds For [ProposalItemKind.AddTags] and [ProposalItemKind.RemoveTags]:
     *                       pre-resolved tag IDs from the caller's tag lookup.
     */
    fun planTaskItem(item: ProposalItem, task: Task?, resolvedTagIds: Set<TagId> = emptySet()): ProposalPlan {
        val taskId = TaskId(item.targetId.ifBlank { task?.id?.value ?: error("No task id") })
        val t = task ?: error("Task ${item.targetId} not found")
        return when (val kind = item.kind) {
            is ProposalItemKind.SetTaskField -> {
                // Validation here, not in dispatch: a value the task cannot hold must fail
                // *before* the claim, or the item burns its one decision on a write that throws.
                ProposalPlan.WriteTask(withField(t, kind.field, kind.value))
            }

            is ProposalItemKind.SetTaskTags -> {
                ProposalPlan.SetTaskTags(taskId, kind.tagIds.map(TagId::fromString).toSet())
            }

            is ProposalItemKind.AddTags -> {
                require(resolvedTagIds.isNotEmpty()) { "AddTags has no resolved tag ids" }
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

            else -> error("Unexpected task-bound kind: $kind")
        }
    }

    /**
     * Returns [task] with [field] set to [rawValue], or throws if the value is not
     * something the task can hold.
     *
     * Pure: reads nothing and writes nothing, so every rejection reason is
     * reachable from a unit test without a repository.
     */
    fun withField(task: Task, field: TaskField, rawValue: String): Task = when (field) {
        TaskField.Title -> {
            val value = rawValue.trim()
            require(value.isNotEmpty()) { "Proposed title is blank" }
            task.copy(title = value)
        }

        TaskField.Description -> task.copy(description = rawValue.trim().takeIf(String::isNotEmpty))

        // The names are matched the way the model writes them ("high"), not the way
        // the enum spells them ("High"). A value the user approved and then
        // cannot apply is a proposal that burned the user's attention for nothing.
        TaskField.Priority -> task.copy(
            priority = TaskPriority.entries.firstOrNull { it.name.equals(rawValue.trim(), ignoreCase = true) }
                ?: error("Unknown priority '$rawValue'"),
        )

        TaskField.DueDate -> {
            val value = rawValue.trim()
            task.copy(dueDate = if (value.isEmpty()) null else parseDate(value))
        }

        TaskField.Status -> {
            val status = TaskStatus.entries.firstOrNull { it.name.equals(rawValue.trim(), ignoreCase = true) }
                ?: error("Unknown status '$rawValue'")
            task.copy(completedAt = if (status == TaskStatus.Completed) clock.now() else null)
        }

        TaskField.EstimateMinutes -> {
            val value = rawValue.trim().toIntOrNull()
                ?: error("Estimate '$rawValue' is not a whole number of minutes")
            require(value >= 0) { "Estimate cannot be negative" }
            task.copy(estimateMinutes = value)
        }

        TaskField.Kind -> task.copy(
            kind = TaskKind.entries.firstOrNull { it.name.equals(rawValue.trim(), ignoreCase = true) }
                ?: error("Unknown kind '$rawValue'"),
        )

        TaskField.DueTime -> {
            val value = rawValue.trim()
            task.copy(dueTime = if (value.isEmpty()) null else parseTime(value))
        }

        TaskField.Project -> {
            val value = rawValue.trim()
            task.copy(projectId = if (value.isEmpty()) null else ProjectId.fromString(value))
        }

        TaskField.Someday -> task.copy(someday = rawValue.trim().equals("true", ignoreCase = true))
    }

    /**
     * Returns [project] with [field] set to [rawValue], or throws if the value is not
     * something the project can hold.
     */
    fun withProjectField(project: Project, field: ProjectField, rawValue: String): Project = when (field) {
        ProjectField.Name -> {
            val value = rawValue.trim()
            require(value.isNotEmpty()) { "Proposed project name is blank" }
            project.copy(name = value)
        }

        ProjectField.Description -> project.copy(description = rawValue.trim().takeIf(String::isNotEmpty))

        ProjectField.Color -> {
            val value = rawValue.trim().toIntOrNull()
                ?: error("Colour '$rawValue' is not an ARGB integer")
            project.copy(color = value)
        }

        ProjectField.Icon -> project.copy(icon = rawValue.trim().takeIf(String::isNotEmpty))
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
            priority = TaskPriority.entries.firstOrNull { it.name.equals(proposed.priority.trim(), ignoreCase = true) }
                ?: error("Unknown priority '${proposed.priority}'"),
            kind = TaskKind.entries.firstOrNull { it.name.equals(proposed.kind.trim(), ignoreCase = true) }
                ?: error("Unknown kind '${proposed.kind}'"),
            projectId = proposed.projectId?.takeIf { it.isNotBlank() }?.let { ProjectId.fromString(it) },
            parentTaskId = proposed.parentTaskId?.takeIf { it.isNotBlank() }?.let { TaskId.fromString(it) },
            tags = proposed.tagIds.map { TagId.fromString(it) },
            dueDate = proposed.dueDate?.takeIf { it.isNotBlank() }?.let { parseDate(it) },
            dueTime = proposed.dueTime?.takeIf { it.isNotBlank() }?.let { parseTime(it) },
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseDate(raw: String): LocalDate = runCatching { LocalDate.parse(raw) }
        .getOrElse { error("'$raw' is not an ISO date (yyyy-mm-dd)") }

    private fun parseTime(raw: String): kotlinx.datetime.LocalTime = runCatching { LocalTimeFormats.parse(raw) }
        .getOrElse { error("'$raw' is not a time (hh:mm)") }

    private fun List<String>.cleaned(): List<String> = map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
