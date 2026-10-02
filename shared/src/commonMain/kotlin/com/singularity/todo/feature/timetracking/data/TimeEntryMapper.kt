package com.singularity.todo.feature.timetracking.data

import com.singularity.todo.core.ids.TimeEntryId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.sync.Hlc
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.timetracking.TimeEntry
import com.singularity.todo.feature.timetracking.TimeEntryKind
import com.singularity.todo.feature.timetracking.TimeEntrySource
import kotlin.time.Instant

/**
 * Maps between [TimeEntryEntity] (database) and [TimeEntry] (domain).
 */
fun TimeEntryEntity.toDomain(): TimeEntry = TimeEntry(
    id = TimeEntryId(id),
    taskId = TaskId(taskId),
    userId = UserId(userId),
    startedAt = Instant.fromEpochMilliseconds(startedAt),
    endedAt = endedAt?.let { Instant.fromEpochMilliseconds(it) },
    kind = TimeEntryKind.valueOf(kind),
    source = TimeEntrySource.valueOf(source),
    note = note,
    createdAt = Instant.fromEpochMilliseconds(createdAt),
    updatedAt = Instant.fromEpochMilliseconds(updatedAt),
    deletedAt = deletedAt?.let { Instant.fromEpochMilliseconds(it) },
    serverVersion = sync.serverVersion,
    hlc = sync.hlc?.let { Hlc.parse(it) },
)

fun TimeEntry.toEntity(): TimeEntryEntity = TimeEntryEntity(
    id = id.value,
    taskId = taskId.value,
    userId = userId.value,
    startedAt = startedAt.toEpochMilliseconds(),
    endedAt = endedAt?.toEpochMilliseconds(),
    kind = kind.name,
    source = source.name,
    note = note,
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = updatedAt.toEpochMilliseconds(),
    deletedAt = deletedAt?.toEpochMilliseconds(),
    sync = com.singularity.todo.core.database.SyncColumns(
        serverVersion = serverVersion,
        hlc = hlc?.toString(),
    ),
)
