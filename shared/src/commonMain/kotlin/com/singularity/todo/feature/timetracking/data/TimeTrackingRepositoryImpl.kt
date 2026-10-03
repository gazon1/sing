package com.singularity.todo.feature.timetracking.data

import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.ids.TimeEntryId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.timetracking.domain.TimeEntry
import com.singularity.todo.feature.timetracking.domain.TimeEntryKind
import com.singularity.todo.feature.timetracking.domain.TimeEntrySource
import com.singularity.todo.feature.timetracking.domain.TimeTrackingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/**
 * Room-backed implementation of [TimeTrackingRepository].
 *
 * Enforces single-open-entry invariant: [startEntry] fails if the user
 * already has a running entry.
 */
class TimeTrackingRepositoryImpl(
    private val dao: TimeEntryDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) : TimeTrackingRepository {

    override fun watchEntries(taskId: TaskId): Flow<List<TimeEntry>> =
        dao.watchForTask(taskId.value).map { list -> list.map { it.toDomain() } }

    override suspend fun getOpenEntry(userId: UserId): TimeEntry? = dao.getOpenEntry(userId.value)?.toDomain()

    override suspend fun startEntry(
        taskId: TaskId,
        userId: UserId,
        kind: TimeEntryKind,
        source: TimeEntrySource,
    ): Result<TimeEntryId> = runCatching {
        // Reject if user already has an open entry
        val existing = dao.getOpenEntry(userId.value)
        require(existing == null) { "User ${userId.value} already has an open time entry" }

        val now = clock.now()
        val id = TimeEntryId.generate()
        val entity = TimeEntryEntity(
            id = id.value,
            taskId = taskId.value,
            userId = userId.value,
            startedAt = now.toEpochMilliseconds(),
            endedAt = null,
            kind = kind.name,
            source = source.name,
            note = null,
            createdAt = now.toEpochMilliseconds(),
            updatedAt = now.toEpochMilliseconds(),
            deletedAt = null,
            sync = SyncColumns(),
        )
        dao.upsert(entity)
        id
    }

    override suspend fun stopEntry(userId: UserId): Result<TimeEntry?> = runCatching {
        val now = clock.now()
        val open = dao.getOpenEntry(userId.value)
            ?: return@runCatching null
        val rows = dao.stopEntry(open.id, now.toEpochMilliseconds(), now.toEpochMilliseconds(), userId.value)
        require(rows > 0) { "Failed to stop entry ${open.id}" }
        dao.getOpenEntry(userId.value) // re-fetch with endedAt set
            ?.copy(endedAt = now.toEpochMilliseconds())
            ?.toDomain()
    }

    override suspend fun createManualEntry(
        taskId: TaskId,
        userId: UserId,
        startedAt: Long,
        endedAt: Long,
        kind: TimeEntryKind,
        note: String?,
        source: TimeEntrySource,
    ): Result<TimeEntryId> = runCatching {
        require(endedAt > startedAt) { "endedAt ($endedAt) must be after startedAt ($startedAt)" }

        val now = clock.now().toEpochMilliseconds()
        val id = TimeEntryId.generate()
        val entity = TimeEntryEntity(
            id = id.value,
            taskId = taskId.value,
            userId = userId.value,
            startedAt = startedAt,
            endedAt = endedAt,
            kind = kind.name,
            source = source.name,
            note = note,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            sync = SyncColumns(),
        )
        dao.upsert(entity)
        id
    }

    override suspend fun updateNote(entryId: TimeEntryId, userId: UserId, note: String?): Result<Unit> = runCatching {
        val now = clock.now().toEpochMilliseconds()
        val rows = dao.updateNote(entryId.value, note, now, userId.value)
        require(rows > 0) { "Entry $entryId not found or not owned by $userId" }
    }

    override suspend fun delete(entryId: TimeEntryId): Result<Unit> = runCatching {
        val uid = currentUser.scopedUserId.value
        val now = clock.now().toEpochMilliseconds()
        val rows = dao.softDelete(entryId.value, now, uid.value)
        require(rows > 0) { "Entry $entryId not found or not owned" }
    }

    override fun watchEntriesInRange(startMs: Long, endMs: Long): Flow<List<TimeEntry>> =
        currentUser.scopedUserId.flatMapLatest { userId ->
            dao.watchForUserInRange(userId.value, startMs, endMs).map { list -> list.map { it.toDomain() } }
        }
}
