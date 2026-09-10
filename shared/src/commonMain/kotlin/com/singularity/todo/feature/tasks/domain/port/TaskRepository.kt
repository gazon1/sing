package com.singularity.todo.feature.tasks.domain.port

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

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
    /** Returns direct child tasks of the given parent. */
    fun watchSubtasks(parentId: TaskId, userId: UserId): Flow<List<Task>>
    fun getTagIds(taskId: TaskId): Flow<List<TagId>>
    suspend fun exists(id: TaskId): Boolean
    suspend fun getById(id: TaskId): Task?
}
