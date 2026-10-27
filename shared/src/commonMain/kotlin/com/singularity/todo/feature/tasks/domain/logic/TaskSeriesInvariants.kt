package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskSharedField
import com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState

/**
 * The rules a series keeps, whoever wrote to it.
 *
 * ## The rule
 *
 * A series is several rows that stand for one piece of work. The fields that
 * describe *the work* — the title, the project, the tags, the priority — have to
 * say the same thing on every row, because there is no rule that says which row
 * is the right one. The fields that describe *an occurrence* — the date, the
 * time, the completion state — are allowed to differ.
 *
 * The rule is not decoration. It is what makes "apply to all of them" a
 * sentence about the series rather than about one row: if the rows already
 * disagree, the sentence has no subject.
 *
 * ## Why the divergence can happen at all
 *
 * The editor writes a shared field to the whole series — that is what
 * [TaskSharedField] is for. But the editor is not the only writer:
 *
 * - an MCP or an AI tool renames one occurrence;
 * - a push from another device brings a row with a different title;
 * - a backup restore writes rows one by one.
 *
 * Each of those writes one row. The series is then two things at once, and the
 * next person to look at it cannot tell which title is the work's.
 *
 * ## What this does about it
 *
 * Two rules, and they are deliberately different:
 *
 * 1. **The write path heals.** A row that is written is the row that says what
 *    the work is; the other rows are realigned to it. The series is repaired by
 *    the write, not by a hope that the next series edit comes along.
 * 2. **The series write path refuses.** A batch that claims to be one series
 *    but carries two series ids, or two titles, is a mistake in the caller —
 *    and a mistake that is written is a series that cannot be described.
 *
 * The structural rules — is this batch one series, may this row carry a rule —
 * live in [TaskSeriesShape]. This object answers the other question: do the rows
 * of a series say the same thing about the work.
 *
 * @see TaskSharedField
 * @see TaskSeriesShape
 */
object TaskSeriesInvariants {

    /**
     * The shared fields of a task, as the values that must agree.
     *
     * The keys are the fields themselves, so a field added to
     * [TaskSharedField] is covered here by construction — a list of names in
     * this file would be a second list to keep in step.
     */
    fun sharedFieldsOf(task: Task): Map<TaskSharedField, Any?> = buildMap {
        for (field in TaskSharedField.entries) {
            put(field, valueOf(task, field))
        }
    }

    /**
     * The fields on which the members of a series disagree.
     *
     * Empty means the series says one thing about the work. A series of one
     * member trivially agrees with itself.
     */
    fun divergedFields(members: List<Task>): List<TaskSharedField> {
        val first = members.firstOrNull() ?: return emptyList()
        val reference = sharedFieldsOf(first)
        return TaskSharedField.entries.filter { field ->
            members.any { member -> sharedFieldsOf(member)[field] != reference[field] }
        }
    }

    /**
     * The members with the shared fields of [source] written onto them.
     *
     * The occurrences keep what belongs to them — the date, the time, the
     * completion state — and take the work's fields from [source].
     */
    fun alignedTo(members: List<Task>, source: Task): List<Task> {
        val shared = sharedFieldsOf(source)
        return members.map { member ->
            var aligned = member
            for (field in TaskSharedField.entries) {
                aligned = aligned.withSharedField(field, shared.getValue(field))
            }
            aligned
        }
    }

    /**
     * The members that have to be written for the series to say one thing.
     *
     * The member that was written is not among them — it is the source. The
     * others are only written when they actually differ, so a change to an
     * occurrence does not rewrite the whole series for nothing.
     *
     * [fields] narrows what the repair compares and copies. It exists for the
     * writer that has no claim about one of the shared fields: a document that
     * says nothing about the tags cannot say that the tags of the other
     * members are wrong, so the repair leaves them alone instead of taking
     * them off.
     */
    fun membersToRealign(
        members: List<Task>,
        source: Task,
        fields: Collection<TaskSharedField> = TaskSharedField.entries,
    ): List<Task> {
        val shared = sharedFieldsOf(source)
        return members
            .filter { member -> member.id != source.id }
            .filter { member ->
                fields.any { field ->
                    valueOf(member, field) != shared.getValue(field)
                }
            }
            .map { member ->
                var aligned = member
                for (field in fields) {
                    aligned = aligned.withSharedField(field, shared.getValue(field))
                }
                aligned
            }
    }

    /**
     * The value of a shared field of a task.
     *
     * The tags are compared as a set: the same tags in a different order are the
     * same tags, and a comparison that called them different would rewrite every
     * member of a series for nothing.
     */
    private fun valueOf(task: Task, field: TaskSharedField): Any? = when (field) {
        TaskSharedField.Title -> task.title
        TaskSharedField.Description -> task.description
        TaskSharedField.Priority -> task.priority
        TaskSharedField.Project -> task.projectId
        TaskSharedField.Tags -> task.tags.toSet()
        TaskSharedField.AccentColor -> task.accentColor
        TaskSharedField.Emoji -> task.emoji
        TaskSharedField.Estimate -> task.estimateMinutes
        TaskSharedField.Recurrence -> task.recurrence
        TaskSharedField.WorkflowState -> task.workflowState
    }

    /**
     * A task with one shared field replaced.
     */
    private fun Task.withSharedField(field: TaskSharedField, value: Any?): Task = when (field) {
        TaskSharedField.Title -> copy(title = value as String)
        TaskSharedField.Description -> copy(description = value as String?)
        TaskSharedField.Priority -> copy(priority = value as TaskPriority)
        TaskSharedField.Project -> copy(projectId = value as ProjectId?)
        TaskSharedField.Tags -> copy(tags = (value as Set<TagId>).toList())
        TaskSharedField.AccentColor -> copy(accentColor = value as Long?)
        TaskSharedField.Emoji -> copy(emoji = value as String?)
        TaskSharedField.Estimate -> copy(estimateMinutes = value as Int?)
        TaskSharedField.Recurrence -> copy(recurrence = value as RecurrenceSpec?)
        TaskSharedField.WorkflowState -> copy(workflowState = value as TaskWorkflowState)
    }
}
