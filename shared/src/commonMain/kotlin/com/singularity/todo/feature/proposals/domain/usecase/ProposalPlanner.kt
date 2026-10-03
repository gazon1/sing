package com.singularity.todo.feature.proposals.domain.usecase

import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.TaskField
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
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
 * @param clock For timestamp generation in subtask creation.
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

        TaskField.Priority -> task.copy(
            priority = runCatching { TaskPriority.valueOf(rawValue.trim()) }
                .getOrElse { error("Unknown priority '$rawValue'") },
        )

        TaskField.DueDate -> {
            val value = rawValue.trim()
            task.copy(dueDate = if (value.isEmpty()) null else parseDate(value))
        }

        TaskField.Status -> {
            val status = runCatching { TaskStatus.valueOf(rawValue.trim()) }
                .getOrElse { error("Unknown status '$rawValue'") }
            task.copy(completedAt = if (status == TaskStatus.Completed) clock.now() else null)
        }

        TaskField.EstimateMinutes -> {
            val value = rawValue.trim().toIntOrNull()
                ?: error("Estimate '$rawValue' is not a whole number of minutes")
            require(value >= 0) { "Estimate cannot be negative" }
            task.copy(estimateMinutes = value)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseDate(raw: String): LocalDate = runCatching { LocalDate.parse(raw) }
        .getOrElse { error("'$raw' is not an ISO date (yyyy-mm-dd)") }

    private fun List<String>.cleaned(): List<String> = map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
