package com.singularity.todo.feature.tasks

import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.TaskEntity
import com.singularity.todo.core.database.TaskTagCrossRef
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tags.TagId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class TaskRepository(
    private val taskDao: TaskDao,
    private val clock: Clock
) {
    fun watchTasks(userId: UserId, filter: TaskFilter): Flow<List<Task>> {
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
            is TaskFilter.ByTag -> flowOf(emptyList()) // TODO: implement tag filtering
            is TaskFilter.Search -> taskDao.search(filter.query).map { it.map { e -> e.toTask() } }
        }
    }

    fun watchTask(id: TaskId): Flow<Task?> {
        return taskDao.watchById(id.value).map { it?.toTask() }
    }

    suspend fun create(task: Task): Result<Unit> = runCatching {
        taskDao.upsert(task.toEntity())
        task.tags.forEach { tagId ->
            taskDao.upsertTagCrossRef(TaskTagCrossRef(taskId = task.id.value, tagId = tagId.value))
        }
    }

    suspend fun update(task: Task): Result<Unit> = runCatching {
        taskDao.upsert(task.toEntity())
    }

    suspend fun softDelete(id: TaskId): Result<Unit> = runCatching {
        val ts = clock.now().toEpochMilliseconds()
        taskDao.softDelete(id.value, ts)
    }

    suspend fun restore(id: TaskId): Result<Unit> = runCatching {
        val ts = clock.now().toEpochMilliseconds()
        taskDao.restore(id.value, ts)
    }

    suspend fun toggleComplete(id: TaskId): Result<Unit> = runCatching {
        val task = taskDao.watchById(id.value).first() ?: return@runCatching
        val ts = clock.now().toEpochMilliseconds()
        if (task.completedAt != null) {
            taskDao.markIncomplete(id.value, ts)
        } else {
            taskDao.markComplete(id.value, ts)
        }
    }

    fun getTagIds(taskId: TaskId): Flow<List<TagId>> {
        return taskDao.getTagIdsForTask(taskId.value).map { it.map { id -> TagId.fromString(id) } }
    }

    suspend fun setTags(taskId: TaskId, tagIds: List<TagId>): Result<Unit> = runCatching {
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

private fun TaskEntity.toTask(): Task = Task(
    id = TaskId.fromString(id),
    title = title,
    description = description,
    priority = priority,
    kind = kind,
    projectId = projectId?.let { ProjectId.fromString(it) },
    tags = emptyList(), // loaded separately
    dueDate = dueDate?.let { kotlinx.datetime.LocalDate.parse(it) },
    dueTime = dueTime,
    completedAt = completedAt?.let { kotlinx.datetime.Instant.fromEpochMilliseconds(it) },
    someday = someday,
    archivedAt = archivedAt?.let { kotlinx.datetime.Instant.fromEpochMilliseconds(it) },
    isPinned = isPinned,
    createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(createdAt),
    updatedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(updatedAt),
    userId = UserId.fromString(userId)
)

fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id.value,
    title = title,
    description = description,
    priority = priority,
    kind = kind,
    projectId = projectId?.value,
    dueDate = dueDate?.toString(),
    dueTime = dueTime,
    completedAt = completedAt?.toEpochMilliseconds(),
    someday = someday,
    archivedAt = archivedAt?.toEpochMilliseconds(),
    isPinned = isPinned,
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = updatedAt.toEpochMilliseconds(),
    userId = userId.value
)
