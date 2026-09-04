package com.singularity.todo.feature.archive

import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.Task
import com.singularity.todo.feature.tasks.TaskFilter
import com.singularity.todo.feature.tasks.TaskRepository
import com.singularity.todo.feature.tasks.UserId

/**
 * Bulk-archive completed tasks. The repository owns the side effect;
 * ViewModels only call [archiveCompletedTasks].
 */
interface ArchiveRepository {
    /** Archives all tasks with completed_at != null AND archived_at IS NULL. */
    suspend fun archiveCompletedTasks(): Result<Int>
}

class TaskDaoArchiveRepository(
    private val taskDao: TaskDao,
    private val clock: Clock,
) : ArchiveRepository {
    override suspend fun archiveCompletedTasks(): Result<Int> = runCatching {
        taskDao.archiveCompleted(clock.now().toEpochMilliseconds())
    }
}
