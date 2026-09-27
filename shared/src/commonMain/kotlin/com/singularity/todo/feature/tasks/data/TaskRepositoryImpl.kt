package com.singularity.todo.feature.tasks.data

import com.singularity.todo.core.database.SyncColumns
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.database.toEpochMillisOrNull
import com.singularity.todo.core.database.toIsoOrNull
import com.singularity.todo.core.database.toLocalTimeIsoOrNull
import com.singularity.todo.core.database.toTask
import com.singularity.todo.core.repository.assertCanWrite
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.feature.agenda.domain.logic.toDateRange
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.Task
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
            taskDao.watchById(id.value),
            taskDao.getDependencyIdsForTask(id.value),
            taskDao.getTagIdsForTask(id.value),
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
            clock.now().toEpochMilliseconds() / (24 * 60 * 60 * 1000),
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

    override fun observeDependencies(taskId: TaskId): Flow<Set<TaskId>> =
        taskDao.getDependencyIdsForTask(taskId.value).map { ids -> ids.map { TaskId.fromString(it) }.toSet() }

    override fun observeBlockingBy(taskId: TaskId): Flow<Set<TaskId>> =
        taskDao.getBlockingTaskIdsForTask(taskId.value).map { ids -> ids.map { TaskId.fromString(it) }.toSet() }

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
        // Read-before-write guard: reject updates to non-existent entities.
        // Prevents silent data loss from upsert-on-missing.
        taskDao.getById(item.id.value)
            ?: throw IllegalArgumentException("Task not found: ${item.id.value}")
        taskDao.upsert(item.toEntity())
        saveOutgoingLinks(item.id, item.description)
        syncRepository.enqueue(item)
        item
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
    }

    override suspend fun restore(id: TaskId): Result<Unit> = runCatching {
        val ts = clock.now().toEpochMilliseconds()
        val rows = taskDao.restoreForUser(id.value, ts, currentUser.scopedUserId.value.value)
        require(rows > 0) { "Task $id not found or not owned by current user" }
    }

    override suspend fun toggleComplete(id: TaskId): Result<Unit> = runCatching {
        val task = taskDao.getById(id.value)
            ?: throw IllegalArgumentException("Task not found: ${id.value}")
        val ts = clock.now().toEpochMilliseconds()
        val uid = currentUser.scopedUserId.value.value
        val rows = if (task.completedAt != null) {
            taskDao.markIncompleteForUser(id.value, ts, uid)
        } else {
            taskDao.markCompleteForUser(id.value, ts, uid)
        }
        require(rows > 0) { "Task $id not found or not owned by current user" }
    }

    override suspend fun togglePinned(id: TaskId): Result<Unit> = runCatching {
        val task = taskDao.getById(id.value)
            ?: throw IllegalArgumentException("Task not found: ${id.value}")
        val ts = clock.now().toEpochMilliseconds()
        val rows = taskDao.setPinnedForUser(id.value, !task.isPinned, ts, currentUser.scopedUserId.value.value)
        require(rows > 0) { "Task $id not found or not owned by current user" }
    }

    override fun getTagIds(taskId: TaskId): Flow<List<TagId>> = taskDao.getTagIdsForTask(taskId.value).map {
        it.map { id -> TagId.fromString(id) }
    }

    override suspend fun exists(id: TaskId): Boolean = taskDao.getById(id.value) != null

    override suspend fun get(id: TaskId): Task? = taskDao.getById(id.value)?.toTask()

    override suspend fun setTags(taskId: TaskId, tagIds: List<TagId>): Result<Unit> = runCatching {
        val uid = currentUser.scopedUserId.value.value
        // Touch the owning task first so a foreign id fails here rather than
        // silently "succeeding" with zero cross-refs written.
        require(taskDao.getById(taskId.value) != null) { "Task $taskId not found" }
        val existing = taskDao.getTagIdsForTask(taskId.value).first()
        existing.forEach { tagId ->
            taskDao.removeTagRefForUser(taskId.value, tagId, uid)
        }
        tagIds.forEach { tagId ->
            taskDao.upsertTagCrossRefForUser(taskId.value, tagId.value, uid)
        }
    }

    override suspend fun setDependencies(taskId: TaskId, deps: Set<TaskId>): Result<Unit> = runCatching {
        val uid = currentUser.scopedUserId.value.value
        require(taskDao.getById(taskId.value) != null) { "Task $taskId not found" }
        dependencyValidator.assertNoCycles(taskId, deps).getOrThrow()
        taskDao.clearDependenciesForUser(taskId.value, uid)
        deps.forEach { depId ->
            taskDao.upsertDependencyForUser(taskId.value, depId.value, uid)
        }
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
