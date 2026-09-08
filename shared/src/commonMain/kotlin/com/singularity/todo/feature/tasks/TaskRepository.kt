package com.singularity.todo.feature.tasks

import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.TaskTagCrossRef
import com.singularity.todo.core.database.toEpochMillis
import com.singularity.todo.core.database.toEpochMillisOrNull
import com.singularity.todo.core.database.toId
import com.singularity.todo.core.database.toInstant
import com.singularity.todo.core.database.toInstantOrNull
import com.singularity.todo.core.database.toIsoOrNull
import com.singularity.todo.core.database.toLocalDateOrNull
import com.singularity.todo.core.database.toProjectIdOrNull
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tags.TagId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

interface TaskRepository {
    /** Emits every task after it's created or updated — for SyncEngine observer */
    val changes: SharedFlow<Task>

    suspend fun create(task: Task): Result<Unit>
    suspend fun update(task: Task): Result<Unit>
    suspend fun softDelete(id: TaskId): Result<Unit>
    suspend fun restore(id: TaskId): Result<Unit>
    suspend fun toggleComplete(id: TaskId): Result<Unit>
    suspend fun togglePinned(id: TaskId): Result<Unit>
    suspend fun setTags(taskId: TaskId, tagIds: List<TagId>): Result<Unit>
    fun watchTasks(userId: UserId, filter: TaskFilter): Flow<List<Task>>
    fun watchTask(id: TaskId): Flow<Task?>
    fun getTagIds(taskId: TaskId): Flow<List<TagId>>
    suspend fun exists(id: TaskId): Boolean
}

@OptIn(ExperimentalCoroutinesApi::class)
class TaskRepositoryImpl(
    private val taskDao: TaskDao,
    private val clock: Clock
) : TaskRepository {

    private val _changes = MutableSharedFlow<Task>(extraBufferCapacity = 64)
    override val changes: SharedFlow<Task> = _changes.asSharedFlow()

    override fun watchTasks(userId: UserId, filter: TaskFilter): Flow<List<Task>> {
        val today = LocalDate.fromEpochDays(
            clock.now().toEpochMilliseconds() / (24 * 60 * 60 * 1000)
        ).toString()

        return when (filter) {
            is TaskFilter.Today -> taskDao.watchByDate(userId.value, today).map { it.map { e -> e.toTask() } }
            is TaskFilter.Upcoming -> {
                val endDate = today // simplified
                taskDao.watchUpcoming(userId.value, today, endDate).map { it.map { e -> e.toTask() } }
            }
            is TaskFilter.Someday -> taskDao.watchSomeday(userId.value).map { it.map { e -> e.toTask() } }
            is TaskFilter.Inbox -> taskDao.watchActive(userId.value).map { it.map { e -> e.toTask() } }
            is TaskFilter.Trash -> taskDao.watchTrash(userId.value).map { it.map { e -> e.toTask() } }
            is TaskFilter.All -> taskDao.watchActive(userId.value).map { it.map { e -> e.toTask() } }
            is TaskFilter.ByProject -> taskDao.watchByProject(userId.value, filter.id.value).map { it.map { e -> e.toTask() } }
            is TaskFilter.Pinned -> taskDao.watchPinned(userId.value).map { it.map { e -> e.toTask() } }
            is TaskFilter.ByTag -> flowOf(emptyList()) // TODO: implement tag filtering
            is TaskFilter.Search -> taskDao.search(filter.query).map { it.map { e -> e.toTask() } }
        }
    }

    override fun watchTask(id: TaskId): Flow<Task?> {
        return taskDao.watchById(id.value).map { it?.toTask() }
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

    override fun getTagIds(taskId: TaskId): Flow<List<TagId>> {
        return taskDao.getTagIdsForTask(taskId.value).map { it.map { id -> TagId.fromString(id) } }
    }

    override suspend fun exists(id: TaskId): Boolean =
        taskDao.watchById(id.value).first() != null

    override suspend fun setTags(taskId: TaskId, tagIds: List<TagId>): Result<Unit> = runCatching {
        // Clear existing tags
        val existing = taskDao.getTagIdsForTask(taskId.value).first()
        existing.forEach { tagId ->
            taskDao.removeTagRef(taskId.value, tagId)
        }
        // Add new tags
        tagIds.forEach { tagId ->
            taskDao.upsertTagCrossRef(TaskTagCrossRef(taskId = taskId.value, tagId = tagId.value))
        }
    }
}

internal fun TaskEntity.toTask(): Task = Task(
    id = id.toId(),
    title = title,
    description = description,
    priority = priority,
    kind = kind,
    projectId = projectId.toProjectIdOrNull(),
    parentTaskId = parentTaskId?.toId(),
    tags = emptyList(), // loaded separately
    dueDate = dueDate.toLocalDateOrNull(),
    dueTime = dueTime,
    completedAt = completedAt.toInstantOrNull(),
    someday = someday,
    archivedAt = archivedAt.toInstantOrNull(),
    isPinned = isPinned,
    createdAt = createdAt.toInstant(),
    updatedAt = updatedAt.toInstant(),
    userId = userId.toId()
)

fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id.value,
    title = title,
    description = description,
    priority = priority,
    kind = kind,
    projectId = projectId?.value,
    parentTaskId = parentTaskId?.value,
    dueDate = dueDate?.toIsoOrNull(),
    dueTime = dueTime,
    completedAt = completedAt.toEpochMillisOrNull(),
    someday = someday,
    archivedAt = archivedAt.toEpochMillisOrNull(),
    isPinned = isPinned,
    createdAt = createdAt.toEpochMillis(),
    updatedAt = updatedAt.toEpochMillis(),
    userId = userId.value
)
