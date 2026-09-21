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
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.repository.observeForCurrentUser
import com.singularity.todo.feature.agenda.domain.logic.toDateRange
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
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

    // ── GenericUserScopedRepository ───────────────────────────────────────────

    override fun observeAll(): Flow<List<Task>> =
        currentUser.observeForCurrentUser { uid ->
            taskDao.watchActive(uid.value).map { it.map { e -> e.toTask() } }
        }

    override fun observe(id: TaskId): Flow<Task?> =
        currentUser.observeForCurrentUser { uid ->
            taskDao.watchById(id.value).map { entity ->
                if (entity?.userId == uid.value) entity.toTask() else null
            }
        }

    override fun observeByFilter(filter: TaskFilter): Flow<List<Task>> =
        currentUser.observeForCurrentUser { uid ->
            val today = LocalDate.fromEpochDays(
                clock.now().toEpochMilliseconds() / (24 * 60 * 60 * 1000),
            ).toString()

            when (filter) {
                is TaskFilter.Today -> taskDao.watchByDate(uid.value, today).map { it.map { e -> e.toTask() } }

                is TaskFilter.Upcoming -> {
                    taskDao.watchUpcoming(uid.value, today, today).map { it.map { e -> e.toTask() } }
                }

                is TaskFilter.Someday -> taskDao.watchSomeday(uid.value).map { it.map { e -> e.toTask() } }

                is TaskFilter.Inbox -> taskDao.watchActive(uid.value).map { it.map { e -> e.toTask() } }

                is TaskFilter.Trash -> taskDao.watchTrash(uid.value).map { it.map { e -> e.toTask() } }

                is TaskFilter.All -> taskDao.watchActive(uid.value).map { it.map { e -> e.toTask() } }

                is TaskFilter.ByProject -> taskDao.watchByProject(
                    uid.value,
                    filter.id.value,
                ).map { it.map { e -> e.toTask() } }

                is TaskFilter.Pinned -> taskDao.watchPinned(uid.value).map { it.map { e -> e.toTask() } }

                is TaskFilter.ByTag -> taskDao.watchByTag(uid.value, filter.id.value).map { it.map { e -> e.toTask() } }

                is TaskFilter.Search -> taskDao.watchSearchResults(uid.value, filter.query)
                    .map { list -> list.map { e -> e.toTask() } }

                is TaskFilter.ByDateRange -> taskDao.watchByDateRange(
                    uid.value,
                    filter.from.toString(),
                    filter.to.toString(),
                ).map { list -> list.map { e -> e.toTask() } }

                is TaskFilter.ByStatuses -> flowOf(emptyList()) // implemented in AgendaEngine; here as stub


                is TaskFilter.ByTags -> {
                    val tagIds = filter.ids.map { it.value }
                    if (filter.matchAll) {
                        taskDao.watchByAllTags(uid.value, tagIds, tagIds.size)
                            .map { list -> list.map { e -> e.toTask() } }
                    } else {
                        taskDao.watchByAnyTag(uid.value, tagIds)
                            .map { list -> list.map { e -> e.toTask() } }
                    }
                }

                is TaskFilter.ByPriorities -> taskDao.watchByPriorities(
                    uid.value,
                    filter.priorities.map { it.name },
                ).map { list -> list.map { e -> e.toTask() } }

                is TaskFilter.ByRegexp -> taskDao.watchByRegexp(uid.value, filter.pattern)
                    .map { list -> list.map { e -> e.toTask() } }

                is TaskFilter.ByDateBucket -> {
                    val range = filter.bucket.toDateRange(filter.today)
                    taskDao.watchByDateRange(
                        uid.value,
                        range.from.toString(),
                        range.to.toString(),
                    ).map { list -> list.map { e -> e.toTask() } }
                }
            }
        }

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
        taskDao.getDependencyIdsForTask(taskId.value).map { ids -> ids.map { TaskId.fromString(it) }.toSet() }

    override fun observeBlockingBy(taskId: TaskId): Flow<Set<TaskId>> =
        taskDao.getBlockingTaskIdsForTask(taskId.value).map { ids -> ids.map { TaskId.fromString(it) }.toSet() }

    override suspend fun create(item: Task): Result<Task> = runCatching {
        taskDao.upsert(item.toEntity())
        item.tags.forEach { tagId ->
            taskDao.upsertTagCrossRef(TaskTagCrossRef(taskId = item.id.value, tagId = tagId.value))
        }
        _changes.tryEmit(item)
        item
    }

    override suspend fun update(item: Task): Result<Task> = runCatching {
        taskDao.upsert(item.toEntity())
        _changes.tryEmit(item)
        item
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

    override suspend fun get(id: TaskId): Task? = taskDao.getById(id.value)?.toTask()

    override suspend fun setTags(taskId: TaskId, tagIds: List<TagId>): Result<Unit> = runCatching {
        val existing = taskDao.getTagIdsForTask(taskId.value).first()
        existing.forEach { tagId ->
            taskDao.removeTagRef(taskId.value, tagId)
        }
        tagIds.forEach { tagId ->
            taskDao.upsertTagCrossRef(TaskTagCrossRef(taskId = taskId.value, tagId = tagId.value))
        }
    }

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
