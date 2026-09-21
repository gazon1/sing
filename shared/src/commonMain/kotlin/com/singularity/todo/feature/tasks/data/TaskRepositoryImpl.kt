package com.singularity.todo.feature.tasks.data

import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskDependencyCrossRef
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.TaskTagCrossRef
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.database.toEpochMillisOrNull
import com.singularity.todo.core.database.toIsoOrNull
import com.singularity.todo.core.database.toLocalTimeIsoOrNull
import com.singularity.todo.core.database.toTask
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.agenda.domain.logic.toDateRange
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class TaskRepositoryImpl(
    private val taskDao: TaskDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) : TaskRepository {

    private val _changes = MutableSharedFlow<Task>(extraBufferCapacity = 64)
    override val changes: SharedFlow<Task> = _changes.asSharedFlow()

    // ── User-scoped observation (new API) ──────────────────────────────────────

    override fun observeAllForCurrentUser(): Flow<List<Task>> =
        currentUser.observeForCurrentUser { uid ->
            taskDao.watchActive(uid.value).map { it.map { e -> e.toTask() } }
        }

    override fun observeForCurrentUser(id: TaskId): Flow<Task?> =
        currentUser.observeForCurrentUser { uid ->
            taskDao.watchById(id.value).map { entity ->
                if (entity?.userId == uid.value) entity?.toTask() else null
            }
        }

    override fun observeByFilter(filter: TaskFilter): Flow<List<Task>> =
        currentUser.observeForCurrentUser { uid -> watchTasks(uid, filter) }

    override fun observeByDate(date: LocalDate): Flow<List<Task>> =
        currentUser.observeForCurrentUser { uid ->
            taskDao.watchByDate(uid.value, date.toString()).map { it.map { e -> e.toTask() } }
        }

    override fun observeSubtasks(parentId: TaskId): Flow<List<Task>> =
        currentUser.observeForCurrentUser { uid ->
            taskDao.watchActive(uid.value).map { list ->
                list.filter { it.parentTaskId == parentId.value }.map { it.toTask() }
            }
        }

    override fun observeDependencies(taskId: TaskId): Flow<Set<TaskId>> =
        watchDependencies(taskId) // already id-only, no userId needed

    override fun observeBlockingBy(taskId: TaskId): Flow<Set<TaskId>> =
        watchBlockingBy(taskId) // already id-only, no userId needed

    // ── Legacy observation (Phase 3 — migrate callers to user-scoped API above) ──
    // Kept for AI tools (Commit 4) — do NOT call from new code.
    fun watchTasks(userId: UserId, filter: TaskFilter): Flow<List<Task>> {
        val today = LocalDate.fromEpochDays(
            clock.now().toEpochMilliseconds() / (24 * 60 * 60 * 1000),
        ).toString()

        return when (filter) {
            is TaskFilter.Today -> taskDao.watchByDate(userId.value, today).map { it.map { e -> e.toTask() } }

            is TaskFilter.Upcoming -> {
                taskDao.watchUpcoming(userId.value, today, today).map { it.map { e -> e.toTask() } }
            }

            is TaskFilter.Someday -> taskDao.watchSomeday(userId.value).map { it.map { e -> e.toTask() } }

            is TaskFilter.Inbox -> taskDao.watchActive(userId.value).map { it.map { e -> e.toTask() } }

            is TaskFilter.Trash -> taskDao.watchTrash(userId.value).map { it.map { e -> e.toTask() } }

            is TaskFilter.All -> taskDao.watchActive(userId.value).map { it.map { e -> e.toTask() } }

            is TaskFilter.ByProject -> taskDao.watchByProject(
                userId.value,
                filter.id.value,
            ).map { it.map { e -> e.toTask() } }

            is TaskFilter.Pinned -> taskDao.watchPinned(userId.value).map { it.map { e -> e.toTask() } }

            is TaskFilter.ByTag -> taskDao.watchByTag(userId.value, filter.id.value).map { it.map { e -> e.toTask() } }

            is TaskFilter.Search -> taskDao.watchSearchResults(userId.value, filter.query)
                .map { list -> list.map { e -> e.toTask() } }

            is TaskFilter.ByDateRange -> taskDao.watchByDateRange(
                userId.value,
                filter.from.toString(),
                filter.to.toString(),
            ).map { list -> list.map { e -> e.toTask() } }

            is TaskFilter.ByStatuses -> flowOf(emptyList()) // implemented in AgendaEngine; here as stub


            is TaskFilter.ByTags -> {
                val tagIds = filter.ids.map { it.value }
                if (filter.matchAll) {
                    taskDao.watchByAllTags(userId.value, tagIds, tagIds.size)
                        .map { list -> list.map { e -> e.toTask() } }
                } else {
                    taskDao.watchByAnyTag(userId.value, tagIds)
                        .map { list -> list.map { e -> e.toTask() } }
                }
            }

            is TaskFilter.ByPriorities -> taskDao.watchByPriorities(
                userId.value,
                filter.priorities.map { it.name },
            ).map { list -> list.map { e -> e.toTask() } }

            is TaskFilter.ByRegexp -> taskDao.watchByRegexp(userId.value, filter.pattern)
                .map { list -> list.map { e -> e.toTask() } }

            is TaskFilter.ByDateBucket -> {
                val range = filter.bucket.toDateRange(filter.today)
                taskDao.watchByDateRange(
                    userId.value,
                    range.from.toString(),
                    range.to.toString(),
                ).map { list -> list.map { e -> e.toTask() } }
            }
        }
    }

    fun watchTasksByDate(userId: UserId, date: LocalDate): Flow<List<Task>> =
        taskDao.watchByDate(userId.value, date.toString())
            .map { list -> list.map { it.toTask() } }

    fun watchTask(id: TaskId): Flow<Task?> = taskDao.watchById(id.value).map { it?.toTask() }

    fun watchSubtasks(parentId: TaskId, userId: UserId): Flow<List<Task>> =
        taskDao.watchActive(userId.value).map { list ->
            list.filter { it.parentTaskId == parentId.value }.map { it.toTask() }
        }

    override suspend fun create(task: Task): Result<Unit> = runCatching {
        taskDao.upsert(task.toEntity())
        task.tags.forEach { tagId ->
            taskDao.upsertTagCrossRef(TaskTagCrossRef(taskId = task.id.value, tagId = tagId.value))
        }
        _changes.tryEmit(task)
    }

    override suspend fun update(task: Task): Result<Unit> = runCatching {
        taskDao.upsert(task.toEntity())
        _changes.tryEmit(task)
    }

    override suspend fun delete(id: TaskId): Result<Unit> = softDelete(id)

    override suspend fun softDelete(id: TaskId): Result<Unit> = runCatching {
        val ts = clock.now().toEpochMilliseconds()
        taskDao.softDelete(id.value, ts)
    }

    override suspend fun restore(id: TaskId): Result<Unit> = runCatching {
        val ts = clock.now().toEpochMilliseconds()
        taskDao.restore(id.value, ts)
    }

    override suspend fun toggleComplete(id: TaskId): Result<Unit> = runCatching {
        val task = taskDao.watchById(id.value).first() ?: return@runCatching
        val ts = clock.now().toEpochMilliseconds()
        if (task.completedAt != null) {
            taskDao.markIncomplete(id.value, ts)
        } else {
            taskDao.markComplete(id.value, ts)
        }
    }

    override suspend fun togglePinned(id: TaskId): Result<Unit> = runCatching {
        val task = taskDao.watchById(id.value).first() ?: return@runCatching
        val ts = clock.now().toEpochMilliseconds()
        taskDao.setPinned(id.value, !task.isPinned, ts)
    }

    override fun getTagIds(taskId: TaskId): Flow<List<TagId>> = taskDao.getTagIdsForTask(taskId.value).map {
        it.map { id -> TagId.fromString(id) }
    }

    override suspend fun exists(id: TaskId): Boolean = taskDao.watchById(id.value).first() != null

    override suspend fun getById(id: TaskId): Task? = taskDao.getById(id.value)?.toTask()

    override suspend fun setTags(taskId: TaskId, tagIds: List<TagId>): Result<Unit> = runCatching {
        val existing = taskDao.getTagIdsForTask(taskId.value).first()
        existing.forEach { tagId ->
            taskDao.removeTagRef(taskId.value, tagId)
        }
        tagIds.forEach { tagId ->
            taskDao.upsertTagCrossRef(TaskTagCrossRef(taskId = taskId.value, tagId = tagId.value))
        }
    }

    override fun watchDependencies(taskId: TaskId): Flow<Set<TaskId>> =
        taskDao.getDependencyIdsForTask(taskId.value).map { ids -> ids.map { TaskId.fromString(it) }.toSet() }

    override fun watchBlockingBy(taskId: TaskId): Flow<Set<TaskId>> =
        taskDao.getBlockingTaskIdsForTask(taskId.value).map { ids -> ids.map { TaskId.fromString(it) }.toSet() }

    override suspend fun setDependencies(taskId: TaskId, deps: Set<TaskId>): Result<Unit> = runCatching {
        taskDao.clearDependencies(taskId.value)
        deps.forEach { depId ->
            taskDao.upsertDependency(
                TaskDependencyCrossRef(taskId = taskId.value, dependsOnTaskId = depId.value),
            )
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
    completedAt = completedAt.toEpochMillisOrNull(),
    someday = someday,
    archivedAt = archivedAt.toEpochMillisOrNull(),
    isPinned = isPinned,
    createdAt = createdAt.toEpochMillis(),
    updatedAt = updatedAt.toEpochMillis(),
    userId = userId.value,
)
