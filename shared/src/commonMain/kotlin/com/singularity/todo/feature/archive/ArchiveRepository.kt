package com.singularity.todo.feature.archive

import com.singularity.todo.core.database.TaskDao
import kotlin.time.Clock

/**
 * Bulk-archive completed tasks. The repository owns the side effect;
 * ViewModels only call [archiveCompletedTasks].
 */
class TaskDaoArchiveRepository(private val taskDao: TaskDao, private val clock: Clock) {
    /** Archives all tasks with completed_at != null AND archived_at IS NULL. */
    suspend fun archiveCompletedTasks(): Result<Int> = runCatching {
        taskDao.archiveCompleted(clock.now().toEpochMilliseconds())
    }
}
