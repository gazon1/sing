package com.singularity.todo.feature.tasks.data

import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.database.toEpochMillisOrNull
import com.singularity.todo.core.database.toIsoOrNull
import com.singularity.todo.core.database.toLocalTimeIsoOrNull
import com.singularity.todo.core.database.toTask
import com.singularity.todo.core.platform.TimeConstants
import com.singularity.todo.core.repository.assertCanWrite
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.feature.agenda.domain.logic.toDateRange
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDependency
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.DependencyValidator
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

/**
 * Bundled extras for batch-loading [Task.tags] and [Task.dependsOn].
 */
private data class TaskExtras(val tagsByTask: Map<String, List<String>>, val depsByTask: Map<String, Set<String>>)

@OptIn(ExperimentalCoroutinesApi::class)
class TaskRepositoryImpl(
    private val taskDao: TaskDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
    private val syncRepository: SyncRepository,
    private val dependencyValidator: DependencyValidator,
) : TaskRepository {

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Attaches [Task.tags] and [Task.dependsOn] to every entity in [source]
     * using two non-suspend [Flow] queries.
     *
     * [uid] is the current user id required by [observeForCurrentUser].
     */
    private fun userTasksWithExtras(
        uid: com.singularity.todo.core.ids.UserId,
        source: Flow<List<TaskEntity>>,
    ): Flow<List<Task>> {
        val tagsFlow = taskDao.observeTagCrossRefs(uid.value).map { rows ->
            rows.groupBy { it.taskId }.mapValues { (_, rows) -> rows.map { it.tagId } }
        }
        val depsFlow = taskDao.observeDependencyCrossRefs(uid.value).map { rows ->
            rows.groupBy { it.taskId }.mapValues { (_, rows) -> rows.map { it.dependsOnTaskId }.toSet() }
        }
        val extrasFlow = combine(tagsFlow, depsFlow) { tags, deps ->
            TaskExtras(tagsByTask = tags, depsByTask = deps)
        }
        return combine(source, extrasFlow) { rows, extras ->
            rows.map { e ->
                e.toTask(
                    tags = extras.tagsByTask[e.id].orEmpty().map { TagId.fromString(it) },
                    dependsOn = extras.depsByTask[e.id].orEmpty().map { TaskId.fromString(it) }.toSet(),
                )
            }
        }
    }

    // ── GenericUserScopedRepository ───────────────────────────────────────────

    override suspend fun currentUserId(): com.singularity.todo.core.ids.UserId = currentUser.scopedUserId.value

    override fun observeAll(): Flow<List<Task>> = currentUser.observeForCurrentUser { uid ->
        userTasksWithExtras(uid, taskDao.watchActive(uid.value))
    }

    override fun observe(id: TaskId): Flow<Task?> = currentUser.observeForCurrentUser { uid ->
        combine(
            taskDao.watchByIdForUser(id.value, uid.value),
            taskDao.getDependencyIdsForUser(id.value, uid.value),
            taskDao.getTagIdsForUser(id.value, uid.value),
        ) { entity, depIds, tagIds ->
            if (entity?.userId == uid.value) {
                entity.toTask(
                    dependsOn = depIds.map { TaskId.fromString(it) }.toSet(),
                    tags = tagIds.map { TagId.fromString(it) },
                )
            } else {
                null
            }
        }
    }

    override fun observeByFilter(filter: TaskFilter): Flow<List<Task>> = currentUser.observeForCurrentUser { uid ->
        val today = LocalDate.fromEpochDays(
            clock.now().toEpochMilliseconds() / TimeConstants.MILLIS_PER_DAY,
        ).toString()

        val entityFlow: Flow<List<TaskEntity>> = when (filter) {
            is TaskFilter.Today -> taskDao.watchByDate(uid.value, today)

            is TaskFilter.Upcoming -> taskDao.watchUpcoming(uid.value, today, today)

            is TaskFilter.Someday -> taskDao.watchSomeday(uid.value)

            is TaskFilter.Inbox -> taskDao.watchActive(uid.value)

            is TaskFilter.Trash -> taskDao.watchTrash(uid.value)

            is TaskFilter.All -> taskDao.watchActive(uid.value)

            is TaskFilter.ByProject -> taskDao.watchByProject(uid.value, filter.id.value)

            is TaskFilter.Pinned -> taskDao.watchPinned(uid.value)

            is TaskFilter.ByTag -> taskDao.watchByTag(uid.value, filter.id.value)

            is TaskFilter.Search -> taskDao.watchSearchResults(uid.value, filter.query)

            is TaskFilter.ByDateRange -> taskDao.watchByDateRange(
                uid.value,
                filter.from.toString(),
                filter.to.toString(),
            )

            is TaskFilter.ByStatuses -> flowOf(emptyList())

            is TaskFilter.ByTags -> {
                val tagIds = filter.ids.map { it.value }
                if (filter.matchAll) {
                    taskDao.watchByAllTags(uid.value, tagIds, tagIds.size)
                } else {
                    taskDao.watchByAnyTag(uid.value, tagIds)
                }
            }

            is TaskFilter.ByPriorities -> taskDao.watchByPriorities(
                uid.value,
                filter.priorities.map { it.name },
            )

            is TaskFilter.ByRegexp -> taskDao.watchByRegexp(uid.value, filter.pattern)

            is TaskFilter.ByDateBucket -> {
                val range = filter.bucket.toDateRange(filter.today)
                taskDao.watchByDateRange(uid.value, range.from.toString(), range.to.toString())
            }
        }

        userTasksWithExtras(uid, entityFlow)
    }

    override fun observeByDate(date: LocalDate): Flow<List<Task>> = currentUser.observeForCurrentUser { uid ->
        userTasksWithExtras(uid, taskDao.watchByDate(uid.value, date.toString()))
    }

    override fun observeSubtasks(parentId: TaskId): Flow<List<Task>> = currentUser.observeForCurrentUser { uid ->
        userTasksWithExtras(
            uid,
            taskDao.watchActive(uid.value).map { rows ->
                rows.filter { it.parentTaskId == parentId.value }
            },
        )
    }

    override fun observeDependencies(taskId: TaskId): Flow<Set<TaskId>> = currentUser.observeForCurrentUser { uid ->
        taskDao.getDependencyIdsForUser(taskId.value, uid.value)
            .map { ids -> ids.map { TaskId.fromString(it) }.toSet() }
    }

    override fun observeBlockingBy(taskId: TaskId): Flow<Set<TaskId>> = currentUser.observeForCurrentUser { uid ->
        taskDao.getBlockingTaskIdsForUser(taskId.value, uid.value)
            .map { ids -> ids.map { TaskId.fromString(it) }.toSet() }
    }

    override fun observeTypedDependencies(taskId: TaskId): Flow<List<TaskDependency>> =
        currentUser.observeForCurrentUser { uid ->
            taskDao.observeTypedDependenciesForUser(taskId.value, uid.value)
                .map { rows ->
                    rows.map { row ->
                        TaskDependency(
                            ownerTaskId = TaskId.fromString(row.taskId),
                            dependencyTaskId = TaskId.fromString(row.dependsOnTaskId),
                            verb = DependencyVerb.valueOf(row.verb),
                        )
                    }
                }
        }

    override suspend fun create(item: Task): Result<Task> = runCatching {
        val currentUid = currentUser.scopedUserId.value
        currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
        val toInsert = item.copy(userId = currentUid)
        taskDao.upsert(toInsert.toEntity())
        saveOutgoingLinks(toInsert.id, toInsert.description)
        toInsert.tags.forEach { tagId ->
            taskDao.upsertTagCrossRefForUser(toInsert.id.value, tagId.value, currentUid.value)
        }
        syncRepository.enqueue(toInsert)
        toInsert
    }

    override suspend fun update(item: Task): Result<Task> = runCatching {
        currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
        // Read-before-write guard: reject updates to non-existent entities.
        // Prevents silent data loss from upsert-on-missing.
        taskDao.getByIdForUser(item.id.value, currentUser.scopedUserId.value.value)
            ?: throw IllegalArgumentException("Task not found: ${item.id.value}")
        // Re-stamp after the guard, exactly as `create` does: the guard has just
        // established that userId is either current or anonymous, so normalising
        // anonymous -> current cannot lose information, whereas upserting the
        // caller's anonymous id verbatim would orphan the row.
        val toUpdate = item.copy(userId = currentUser.scopedUserId.value)
        taskDao.upsert(toUpdate.toEntity())
        saveOutgoingLinks(toUpdate.id, toUpdate.description)
        syncRepository.enqueue(toUpdate)
        toUpdate
    }

    private suspend fun saveOutgoingLinks(id: TaskId, description: String?) {
        val links = description?.let { extractOutgoingLinks(it) }.orEmpty()
        val rows = taskDao.setOutgoingLinksForUser(
            id = id.value,
            linksJson = links.toLinksJson(),
            updatedAt = clock.now().toEpochMilliseconds(),
            userId = currentUser.scopedUserId.value.value,
        )
        require(rows > 0) { "Outgoing links not written for $id — not found or not owned by current user" }
    }

    /**
     * Re-reads [id] together with its cross-refs and pushes that state to the sync
     * outbox.
     *
     * The narrow field-update methods below write through a targeted `UPDATE` /
     * cross-ref insert rather than a whole-row upsert, so the caller's copy of the
     * task is stale by the time the write lands. Re-reading is what makes the pushed
     * payload match what is actually in the database — including for deletes, which
     * propagate as state (`archivedAt`) rather than as a tombstone. `buildPatch`
     * already ships the full snapshot, so no protocol change is involved.
     */
    private suspend fun enqueueFresh(id: TaskId) {
        val row = taskDao.getByIdForUser(id.value, currentUser.scopedUserId.value.value) ?: return
        val tags = taskDao.getTagIdsForUser(id.value, currentUser.scopedUserId.value.value).first()
            .map { TagId.fromString(it) }
        val deps = taskDao.getDependencyIdsForUser(id.value, currentUser.scopedUserId.value.value)
            .first()
            .map { TaskId.fromString(it) }
            .toSet()
        syncRepository.enqueue(row.toTask(tags = tags, dependsOn = deps))
    }

    override suspend fun delete(id: TaskId): Result<Unit> = softDelete(id)

    // ── Remote apply (pull handler) ────────────────────────────────────────────

    override suspend fun upsert(task: Task): Task {
        taskDao.upsert(task.toEntity())
        return task
    }

    override suspend fun softDelete(id: TaskId): Result<Unit> = runCatching {
        val ts = clock.now().toEpochMilliseconds()
        val rows = taskDao.softDeleteForUser(id.value, ts, currentUser.scopedUserId.value.value)
        require(rows > 0) { "Task $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    override suspend fun restore(id: TaskId): Result<Unit> = runCatching {
        val ts = clock.now().toEpochMilliseconds()
        val rows = taskDao.restoreForUser(id.value, ts, currentUser.scopedUserId.value.value)
        require(rows > 0) { "Task $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    override suspend fun toggleComplete(id: TaskId): Result<Unit> = runCatching {
        val task = taskDao.getByIdForUser(id.value, currentUser.scopedUserId.value.value)
            ?: throw IllegalArgumentException("Task not found: ${id.value}")
        val ts = clock.now().toEpochMilliseconds()
        val uid = currentUser.scopedUserId.value.value
        val rows = if (task.completedAt != null) {
            taskDao.markIncompleteForUser(id.value, ts, uid)
        } else {
            taskDao.markCompleteForUser(id.value, ts, uid)
        }
        require(rows > 0) { "Task $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    override suspend fun togglePinned(id: TaskId): Result<Unit> = runCatching {
        val task = taskDao.getByIdForUser(id.value, currentUser.scopedUserId.value.value)
            ?: throw IllegalArgumentException("Task not found: ${id.value}")
        val ts = clock.now().toEpochMilliseconds()
        val rows = taskDao.setPinnedForUser(id.value, !task.isPinned, ts, currentUser.scopedUserId.value.value)
        require(rows > 0) { "Task $id not found or not owned by current user" }
        enqueueFresh(id)
    }

    override fun getTagIds(taskId: TaskId): Flow<List<TagId>> = currentUser.observeForCurrentUser { uid ->
        taskDao.getTagIdsForUser(taskId.value, uid.value).map { it.map { id -> TagId.fromString(id) } }
    }

    override suspend fun exists(id: TaskId): Boolean {
        val uid = currentUser.scopedUserId.value.value
        return taskDao.getByIdForUser(id.value, uid) != null
    }

    override suspend fun get(id: TaskId): Task? {
        // Scoped: the unscoped getById would return another user's task.
        val uid = currentUser.scopedUserId.value.value
        return taskDao.getByIdForUser(id.value, uid)?.toTask()
    }

    override suspend fun setTags(taskId: TaskId, tagIds: List<TagId>): Result<Unit> = runCatching {
        val uid = currentUser.scopedUserId.value.value
        // Touch the owning task first so a foreign id fails here rather than
        // silently "succeeding" with zero cross-refs written.
        require(taskDao.getByIdForUser(taskId.value, uid) != null) { "Task $taskId not found" }
        val existing = taskDao.getTagIdsForUser(taskId.value, uid).first()
        existing.forEach { tagId ->
            taskDao.removeTagRefForUser(taskId.value, tagId, uid)
        }
        tagIds.forEach { tagId ->
            taskDao.upsertTagCrossRefForUser(taskId.value, tagId.value, uid)
        }
        // `tags` is a serialised field of Task, so the cross-ref change is part of
        // the synced state and must be pushed.
        enqueueFresh(taskId)
    }

    override suspend fun setDependencies(taskId: TaskId, deps: Set<TaskId>): Result<Unit> = runCatching {
        val uid = currentUser.scopedUserId.value.value
        require(taskDao.getByIdForUser(taskId.value, uid) != null) { "Task $taskId not found" }
        dependencyValidator.assertNoCycles(taskId, deps).getOrThrow()
        taskDao.clearDependenciesForUser(taskId.value, uid)
        deps.forEach { depId ->
            taskDao.upsertDependencyForUser(taskId.value, depId.value, DependencyVerb.BLOCKS.name, uid)
        }
        // Same as setTags: `dependsOn` is part of the synced payload.
        enqueueFresh(taskId)
    }

    override suspend fun setDependency(
        from: TaskId,
        to: TaskId,
        verb: DependencyVerb,
        enabled: Boolean,
    ): Result<Unit> = runCatching {
        val uid = currentUser.scopedUserId.value.value
        require(taskDao.getByIdForUser(from.value, uid) != null) { "Task $from not found" }
        if (enabled) {
            taskDao.upsertDependencyForUser(from.value, to.value, verb.name, uid)
        } else {
            taskDao.removeDependencyForVerb(from.value, to.value, verb.name, uid)
        }
        enqueueFresh(from)
    }
}

private fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id.value,
    title = title,
    description = description,
    priority = priority,
    kind = kind,
    projectId = projectId?.value,
    parentTaskId = parentTaskId?.value,
    dueDate = dueDate?.toIsoOrNull(),
    dueTime = dueTime.toLocalTimeIsoOrNull(),
    startDate = startDate?.toIsoOrNull(),
    startTime = startTime.toLocalTimeIsoOrNull(),
    endDate = endDate?.toIsoOrNull(),
    endTime = endTime.toLocalTimeIsoOrNull(),
    accentColor = accentColor,
    emoji = emoji,
    completedAt = completedAt.toEpochMillisOrNull(),
    someday = someday,
    archivedAt = archivedAt.toEpochMillisOrNull(),
    isPinned = isPinned,
    recurrenceRule = recurrence?.let { StableJson.encodeToString(RecurrenceSpec.serializer(), it) },
    createdAt = createdAt.toEpochMillis(),
    updatedAt = updatedAt.toEpochMillis(),
    userId = userId.value,
    sync = SyncColumns(serverVersion = serverVersion, hlc = hlc?.encoded),
)
