package com.singularity.todo.core.database

import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.core.ids.UserId
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/**
 * Epoch millis ↔ kotlinx.datetime types.
 */
internal fun Long.toInstant(): Instant = Instant.fromEpochMilliseconds(this)
internal fun Long?.toInstantOrNull(): Instant? = this?.toInstant()
internal fun Instant.toEpochMillis(): Long = toEpochMilliseconds()
internal fun Instant?.toEpochMillisOrNull(): Long? = this?.toEpochMillis()

/**
 * ISO-8601 string ↔ kotlinx.datetime.LocalDate.
 */
internal fun String?.toLocalDateOrNull(): LocalDate? = this?.let { LocalDate.parse(it) }
internal fun LocalDate?.toIsoOrNull(): String? = this?.toString()

/**
 * Typed ID factory from String (non-null — fails if blank).
 * Use for Entity → Domain conversion.
 */
@Suppress("UNCHECKED_CAST")
internal inline fun <reified T : Any> String.toId(): T = when (T::class) {
    TaskId::class -> TaskId.fromString(this) as T
    NoteId::class -> NoteId.fromString(this) as T
    ProjectId::class -> ProjectId.fromString(this) as T
    TagId::class -> TagId.fromString(this) as T
    UserId::class -> UserId.fromString(this) as T
    else -> error("Unknown ID type: ${T::class}")
}

/** Returns null if string is null or blank. */
internal fun String?.toProjectIdOrNull(): ProjectId? =
    this?.takeIf { it.isNotBlank() }?.let { ProjectId.fromString(it) }

/**
 * Converts a [TaskEntity] to a domain [Task].
 * Tags are not populated — callers must fill them separately if needed.
 */
internal fun TaskEntity.toTask(): Task = Task(
    id = id.toId(),
    title = title,
    description = description,
    priority = priority,
    kind = kind,
    projectId = projectId.toProjectIdOrNull(),
    parentTaskId = parentTaskId?.toId(),
    tags = emptyList(),
    dueDate = dueDate.toLocalDateOrNull(),
    dueTime = dueTime,
    completedAt = completedAt.toInstantOrNull(),
    someday = someday,
    archivedAt = archivedAt.toInstantOrNull(),
    isPinned = isPinned,
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
    userId = userId.toId(),
)
