package com.singularity.todo.test.fakes

import com.singularity.todo.core.ids.TimeEntryId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.timetracking.domain.TimeEntry
import com.singularity.todo.feature.timetracking.domain.TimeEntryKind
import com.singularity.todo.feature.timetracking.domain.TimeEntrySource
import com.singularity.todo.feature.timetracking.domain.port.TimeTrackingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * In-memory fake of [TimeTrackingRepository] for tests.
 * Does NOT enforce single-open-entry invariant (kept simple for tests).
 */
class FakeTimeTrackingRepository(private val clock: Clock) : TimeTrackingRepository {

    private val entries = MutableStateFlow<Map<String, TimeEntry>>(emptyMap())

    override fun watchEntries(taskId: TaskId): Flow<List<TimeEntry>> =
        entries.map { map -> map.values.filter { it.taskId == taskId }.sortedByDescending { it.startedAt } }

    override suspend fun getOpenEntry(userId: UserId): TimeEntry? =
        entries.value.values.find { it.userId == userId && it.endedAt == null && it.deletedAt == null }

    override suspend fun startEntry(
        taskId: TaskId,
        userId: UserId,
        kind: TimeEntryKind,
        source: TimeEntrySource,
    ): Result<TimeEntryId> {
        val id = TimeEntryId.generate()
        val now = clock.now()
        val entry = TimeEntry(
            id = id,
            taskId = taskId,
            userId = userId,
            startedAt = now,
            endedAt = null,
            kind = kind,
            source = source,
            note = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            serverVersion = 0L,
            hlc = null,
        )
        entries.value = entries.value + (id.value to entry)
        return Result.success(id)
    }

    override suspend fun stopEntry(userId: UserId): Result<TimeEntry?> {
        val open = getOpenEntry(userId) ?: return Result.success(null)
        val now = clock.now()
        val updated = open.copy(endedAt = now, updatedAt = now)
        entries.value = entries.value + (open.id.value to updated)
        return Result.success(updated)
    }

    override suspend fun createManualEntry(
        taskId: TaskId,
        userId: UserId,
        startedAt: Long,
        endedAt: Long,
        kind: TimeEntryKind,
        note: String?,
        source: TimeEntrySource,
    ): Result<TimeEntryId> {
        val id = TimeEntryId.generate()
        val now = clock.now()
        val entry = TimeEntry(
            id = id,
            taskId = taskId,
            userId = userId,
            startedAt = Instant.fromEpochMilliseconds(startedAt),
            endedAt = Instant.fromEpochMilliseconds(endedAt),
            kind = kind,
            source = source,
            note = note,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            serverVersion = 0L,
            hlc = null,
        )
        entries.value = entries.value + (id.value to entry)
        return Result.success(id)
    }

    override suspend fun updateNote(entryId: TimeEntryId, userId: UserId, note: String?): Result<Unit> {
        val existing = entries.value[entryId.value]
            ?: return Result.failure(IllegalStateException("Not found"))
        if (existing.userId != userId) return Result.failure(IllegalStateException("Not owned"))
        val now = clock.now()
        entries.value = entries.value + (entryId.value to existing.copy(note = note, updatedAt = now))
        return Result.success(Unit)
    }

    override suspend fun delete(entryId: TimeEntryId): Result<Unit> {
        entries.value = entries.value - entryId.value
        return Result.success(Unit)
    }

    override fun watchEntriesInRange(startMs: Long, endMs: Long): Flow<List<TimeEntry>> = entries.map { map ->
        map.values
            .filter {
                it.startedAt.toEpochMilliseconds() >= startMs &&
                    it.startedAt.toEpochMilliseconds() < endMs &&
                    it.deletedAt == null
            }
            .sortedByDescending { it.startedAt }
    }
}
