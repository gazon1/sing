package com.singularity.todo.core.backup

import com.singularity.todo.core.database.ChecklistItemEntity
import com.singularity.todo.core.database.LocalTimeFormats
import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.database.toWorkflowState
import com.singularity.todo.core.database.TaskDependencyCrossRef
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.TaskReminderEntity
import com.singularity.todo.core.database.TaskTagCrossRef
import com.singularity.todo.core.database.toLocalTimeOrNull
import com.singularity.todo.feature.timetracking.data.TimeEntryEntity
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/**
 * The task rows of a payload: the task itself, and the rows that hang off it.
 */
// ─── TaskDto ───────────────────────────────────────────────────────────────────

@Serializable
data class TaskDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val priority: String = "None",
    val kind: String = "Task",
    val projectId: String? = null,
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
     * The state the user claimed for this task's work — `Open`, `NextUp` or
     * `InProgress`.
     *
     * A backup written before the field existed restores as `Open`, which is what
     * "nothing claimed" means. A name this build does not know restores as
     * `Open` too, rather than failing the restore.
     */
    val workflowState: String = "Open",
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
        priority = priority.name,
        kind = kind.name,
        projectId = projectId,
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
        workflowState = workflowState,
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
            com.singularity.todo.feature.tasks.domain.model.TaskPriority
                .valueOf(priority),
        kind =
            com.singularity.todo.feature.tasks.domain.model.TaskKind
                .valueOf(kind),
        projectId = projectId,
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
        workflowState = workflowState.toWorkflowState().name,
        outgoingLinks = encodeLinkList(outgoingLinks), // List → JSON string
        aiSuppressedTagIds = encodeLinkList(aiSuppressedTagIds), // List → JSON string
        createdAt = createdAt,
        updatedAt = updatedAt,
        userId = userId,
        sync = SyncColumns(), // a restore is a fresh import: sync state is not carried by a backup
    )
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
