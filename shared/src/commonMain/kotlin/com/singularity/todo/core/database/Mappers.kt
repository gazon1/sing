package com.singularity.todo.core.database

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.Hlc
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.domain.model.TagGroup
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Instant

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
 * ISO-8601 string (HH:mm:ss) ↔ kotlinx.datetime.LocalTime.
 * Uses [LocalTimeFormats.ISO] for stable, sortable wire format.
 */
internal fun String?.toLocalTimeOrNull(): LocalTime? =
    this?.takeIf { it.isNotBlank() }?.let { LocalTimeFormats.parse(it) }
internal fun LocalTime?.toLocalTimeIsoOrNull(): String? = this?.let { LocalTimeFormats.format(it) }

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
 * Tags and dependsOn are populated from the bundled [TaskExtras] when loading lists.
 * For single-task observes use [TaskRepository.observeDependencies] separately.
 */
internal fun TaskEntity.toTask(tags: List<TagId> = emptyList(), dependsOn: Set<TaskId> = emptySet()): Task = Task(
    id = id.toId(),
    title = title,
    description = description,
    priority = priority,
    kind = kind,
    projectId = projectId.toProjectIdOrNull(),
    parentTaskId = parentTaskId?.toId(),
    tags = tags,
    dueDate = dueDate.toLocalDateOrNull(),
    dueTime = dueTime.toLocalTimeOrNull(),
    startDate = startDate.toLocalDateOrNull(),
    startTime = startTime.toLocalTimeOrNull(),
    endDate = endDate.toLocalDateOrNull(),
    endTime = endTime.toLocalTimeOrNull(),
    accentColor = accentColor,
    emoji = emoji,
    completedAt = completedAt.toInstantOrNull(),
    someday = someday,
    archivedAt = archivedAt.toInstantOrNull(),
    isPinned = isPinned,
    dependsOn = dependsOn,
    aiSuppressedTagIds = StableJson.decodeFromString(
        SetSerializer(String.serializer()),
        aiSuppressedTagIds,
    ).map { TagId.fromString(it) }.toSet(),
    recurrence = recurrenceRule?.let {
        StableJson.decodeFromString<RecurrenceSpec>(it)
    },
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
    userId = userId.toId(),
    serverVersion = sync.serverVersion,
    hlc = sync.hlc?.let { Hlc(it) },
)

/**
 * Converts a [ProjectEntity] to a domain [Project].
 */
internal fun ProjectEntity.toProject(): Project = Project(
    id = ProjectId.fromString(id),
    name = name,
    color = color,
    icon = icon,
    description = description,
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
    isDefault = isDefault,
    dueDate = dueDate.toLocalDateOrNull(),
    team = team,
    isDeleted = isDeleted,
    deletedAt = deletedAt.toInstantOrNull(),
    parentId = parentId?.toProjectIdOrNull(),
    sortOrder = sortOrder,
    idempotencyKey = idempotencyKey,
    externalId = externalId,
    userId = UserId(userId),
    serverVersion = sync.serverVersion,
    hlc = sync.hlc?.let { Hlc(it) },
)

/**
 * Converts a [TagGroupEntity] to a domain [TagGroup].
 */
internal fun TagGroupEntity.toTagGroup(): TagGroup = TagGroup(
    id = TagGroupId.fromString(id),
    name = name,
    color = color,
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
    userId = UserId(userId),
    deletedAt = deletedAt.toInstantOrNull(),
    serverVersion = sync.serverVersion,
    hlc = sync.hlc?.let { Hlc(it) },
)

/**
 * Converts a domain [TagGroup] to a [TagGroupEntity] for persistence.
 */
internal fun TagGroup.toEntity(): TagGroupEntity = TagGroupEntity(
    id = id.value,
    userId = userId.value,
    name = name,
    color = color,
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = updatedAt.toEpochMilliseconds(),
    deletedAt = deletedAt.toEpochMillisOrNull(),
    sync = SyncColumns(serverVersion = serverVersion, hlc = hlc?.encoded),
)
