package com.singularity.todo.core.backup

import com.singularity.todo.core.database.ChecklistItemEntity
import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.database.TaskDependencyCrossRef
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.TaskReminderEntity
import com.singularity.todo.core.database.TaskTagCrossRef
import com.singularity.todo.core.database.toLocalTimeOrNull
import com.singularity.todo.core.database.toWorkflowState
import com.singularity.todo.core.format.LocalTimeFormats
import com.singularity.todo.core.serialization.parseWireName
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.timetracking.data.TimeEntryEntity
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

// ─── TaskDto ───────────────────────────────────────────────────────────────────

/**
 * The task rows of a payload: the task itself, and the rows that hang off it.
 */

@Serializable
data class TaskDto(
    val id: String,
    val title: String,
    val description: String? = null,
    /**
     * How urgent the task is, under the name the format gives it: `none`, `low`,
     * `medium`, `high` or `urgent`.
     *
     * The name is a contract of the format, not the name of the Kotlin constant —
     * see [com.singularity.todo.core.serialization.WireNamed]. A payload written
     * before the format named its values (`None`) is still read.
     */
    val priority: String = "none",
    /** `task` or `note` — the kind of row this is, under the format's own name. */
    val kind: String = "task",
    val projectId: String? = null,
    /**
     * The id of the task this one is a child of; `null` for a top-level task.
     *
     * A backup that dropped it gave back a flat list of tasks on restore: the
     * dates survived, the hierarchy did not. A parent that is not part of the
     * same restore — deleted, or never written by this archive — comes back as
     * `null` (see [withResolvedParent]), so this field is the link the account
     * held, not a promise that the parent is in the payload too.
     */
    val parentTaskId: String? = null,
    val dueDate: String? = null,
    @Serializable(with = LocalTimeSerializer::class)
    val dueTime: LocalTime? = null,
    val startDate: String? = null,
    @Serializable(with = LocalTimeSerializer::class)
    val startTime: LocalTime? = null,
    val endDate: String? = null,
    @Serializable(with = LocalTimeSerializer::class)
    val endTime: LocalTime? = null,
    val accentColor: Long? = null,
    val emoji: String? = null,
    val completedAt: Long? = null,
    val someday: Boolean = false,
    val archivedAt: Long? = null,
    val isPinned: Boolean = false,
    val estimateMinutes: Int? = null,
    val recurrenceRule: String? = null,
    /**
     * The id of the series this task belongs to; `null` for a task that stands alone.
     *
     * A backup that dropped it would turn a series into unrelated tasks on restore:
     * the dates would survive, the connection between them would not.
     */
    val seriesId: String? = null,
    /**
     * The state the user claimed for this task's work — `open`, `next_up` or
     * `in_progress`.
     *
     * A backup written before the field existed restores as `open`, which is what
     * "nothing claimed" means. A name this build does not know restores as
     * `open` too, rather than failing the restore. The spelling is the format's
     * own — see [com.singularity.todo.core.serialization.WireNamed] — and a
     * payload that spells it the old way (`Open`) is still read.
     */
    val workflowState: String = "open",
    /** Wikilink targets of this task, stored as a list in JSON and as a JSON array column in Room. */
    val outgoingLinks: List<String> = emptyList(),
    val aiSuppressedTagIds: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)

fun TaskEntity.toDto(): TaskDto =
    TaskDto(
        id = id,
        title = title,
        description = description,
        priority = priority.wireName,
        kind = kind.wireName,
        projectId = projectId,
        parentTaskId = parentTaskId,
        dueDate = dueDate,
        dueTime = dueTime.toLocalTimeOrNull(),
        startDate = startDate,
        startTime = startTime.toLocalTimeOrNull(),
        endDate = endDate,
        endTime = endTime.toLocalTimeOrNull(),
        accentColor = accentColor,
        emoji = emoji,
        completedAt = completedAt,
        someday = someday,
        archivedAt = archivedAt,
        isPinned = isPinned,
        estimateMinutes = estimateMinutes,
        recurrenceRule = recurrenceRule,
        seriesId = seriesId,
        workflowState = workflowState.toWorkflowState().wireName,
        outgoingLinks = decodeLinkList(outgoingLinks), // JSON string → List
        aiSuppressedTagIds = decodeLinkList(aiSuppressedTagIds), // JSON string → List
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun TaskDto.toEntity(userId: String): TaskEntity =
    TaskEntity(
        id = id,
        title = title,
        description = description,
        priority =
            parseWireName<TaskPriority>(priority),
        kind =
            parseWireName<TaskKind>(kind),
        projectId = projectId,
        parentTaskId = parentTaskId,
        dueDate = dueDate,
        dueTime = dueTime?.let { LocalTimeFormats.format(it) },
        startDate = startDate,
        startTime = startTime?.let { LocalTimeFormats.format(it) },
        endDate = endDate,
        endTime = endTime?.let { LocalTimeFormats.format(it) },
        accentColor = accentColor,
        emoji = emoji,
        completedAt = completedAt,
        someday = someday,
        archivedAt = archivedAt,
        isPinned = isPinned,
        estimateMinutes = estimateMinutes,
        recurrenceRule = recurrenceRule,
        seriesId = seriesId,
        // The column keeps the database's own spelling of the state; the payload
        // speaks the format's. The two are read back by the same tolerant reader.
        workflowState = workflowState.toWorkflowState().name,
        outgoingLinks = encodeLinkList(outgoingLinks), // List → JSON string
        aiSuppressedTagIds = encodeLinkList(aiSuppressedTagIds), // List → JSON string
        createdAt = createdAt,
        updatedAt = updatedAt,
        userId = userId,
        sync = SyncColumns(), // a restore is a fresh import: sync state is not carried by a backup
    )

/**
 * The task with a parent link that names a task this restore will not write
 * cleared.
 *
 * A parent can be gone by the time the archive is read: the row was deleted, or
 * the export never included it. The task comes back as a top-level task rather
 * than as the child of a task nothing will ever write — the restore keeps the
 * hierarchy it can prove, and a dangling id would hide the task from every list
 * that asks for top-level tasks.
 */
fun TaskDto.withResolvedParent(knownTaskIds: Set<String>): TaskDto =
    if (parentTaskId != null && parentTaskId !in knownTaskIds) copy(parentTaskId = null) else this
// ─── TaskTagDto ───────────────────────────────────────────────────────────────

@Serializable
data class TaskTagDto(val taskId: String, val tagId: String)

fun TaskTagCrossRef.toDto(): TaskTagDto = TaskTagDto(taskId = taskId, tagId = tagId)

fun TaskTagDto.toEntity(): TaskTagCrossRef = TaskTagCrossRef(taskId = taskId, tagId = tagId)
// ─── TaskDependencyDto (MR-1) ─────────────────────────────────────────────────

/**
 * One edge of the dependency graph.
 *
 * ## Why the verb is here
 *
 * The verb is what the edge *means*: `BLOCKS` says the task waits for the other
 * one, `FOLLOWS_UP` says it comes after it, `DUPLICATES` says it is the same
 * work. A backup that kept the two ends of an edge and dropped the meaning
 * turned every relation into a block — and a task that merely followed up on
 * another came back from the restore waiting for it. The close rule reads
 * exactly these edges, so a restored graph that lies about them refuses a
 * close that has no reason to be refused.
 *
 * The default is `BLOCKS`, which is what the edge meant before the verb was
 * carried: a backup written before this field existed is a graph of blocks.
 */
@Serializable
data class TaskDependencyDto(val taskId: String, val dependsOnTaskId: String, val verb: String = "BLOCKS")

fun TaskDependencyCrossRef.toDto(): TaskDependencyDto =
    TaskDependencyDto(
        taskId = taskId,
        dependsOnTaskId = dependsOnTaskId,
        verb = verb,
    )

fun TaskDependencyDto.toEntity(): TaskDependencyCrossRef =
    TaskDependencyCrossRef(
        taskId = taskId,
        dependsOnTaskId = dependsOnTaskId,
        verb = verb,
    )
// ─── TaskReminderDto (MR-2) ───────────────────────────────────────────────────

@Serializable
data class TaskReminderDto(
    val id: String,
    val taskId: String,
    val type: String, // "gentle" | "annoying"
    val offsetMinutes: Int,
    val fireAt: Long,
    val recurringPattern: String? = null,
    val viewId: String? = null,
    val lastFiredAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

fun TaskReminderEntity.toDto(): TaskReminderDto =
    TaskReminderDto(
        id = id,
        taskId = taskId,
        type = type,
        offsetMinutes = offsetMinutes,
        fireAt = fireAt,
        recurringPattern = recurringPattern,
        viewId = viewId,
        lastFiredAt = lastFiredAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun TaskReminderDto.toEntity(userId: String): TaskReminderEntity =
    TaskReminderEntity(
        id = id,
        taskId = taskId,
        userId = userId,
        type = type,
        offsetMinutes = offsetMinutes,
        fireAt = fireAt,
        recurringPattern = recurringPattern,
        viewId = viewId,
        lastFiredAt = lastFiredAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
// ─── ChecklistItemDto (MR-2) ─────────────────────────────────────────────────

@Serializable
data class ChecklistItemDto(
    val id: String,
    val taskId: String,
    val title: String,
    val isCompleted: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val checkedBy: String? = null,
    val checkedAt: Long? = null,
    val rowVersion: Int = 1,
)

fun ChecklistItemEntity.toDto(): ChecklistItemDto =
    ChecklistItemDto(
        id = id,
        taskId = taskId,
        title = title,
        isCompleted = isCompleted,
        sortOrder = sortOrder,
        createdAt = createdAt,
        updatedAt = updatedAt,
        checkedBy = checkedBy,
        checkedAt = checkedAt,
        rowVersion = rowVersion,
    )

fun ChecklistItemDto.toEntity(): ChecklistItemEntity =
    ChecklistItemEntity(
        id = id,
        taskId = taskId,
        title = title,
        isCompleted = isCompleted,
        sortOrder = sortOrder,
        createdAt = createdAt,
        updatedAt = updatedAt,
        checkedBy = checkedBy,
        checkedAt = checkedAt,
        rowVersion = rowVersion,
    )
// ─── TimeEntryDto (MR-2) ─────────────────────────────────────────────────────

@Serializable
data class TimeEntryDto(
    val id: String,
    val taskId: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val kind: String, // Work | Recording
    val source: String, // Timer | Manual | Pomodoro | AiProposal
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

fun TimeEntryEntity.toDto(): TimeEntryDto =
    TimeEntryDto(
        id = id,
        taskId = taskId,
        startedAt = startedAt,
        endedAt = endedAt,
        kind = kind,
        source = source,
        note = note,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

fun TimeEntryDto.toEntity(userId: String): TimeEntryEntity =
    TimeEntryEntity(
        id = id,
        taskId = taskId,
        userId = userId,
        startedAt = startedAt,
        endedAt = endedAt,
        kind = kind,
        source = source,
        note = note,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        sync = SyncColumns(),
    )
