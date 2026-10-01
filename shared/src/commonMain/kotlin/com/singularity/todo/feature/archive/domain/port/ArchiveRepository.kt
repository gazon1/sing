package com.singularity.todo.feature.archive.domain.port

/**
 * Port for bulk-archiving completed tasks.
 * Platform implementation: [com.singularity.todo.feature.archive.data.TaskDaoArchiveRepository].
 */
interface ArchiveRepository {
    /**
     * Archives tasks with completed_at != null AND archived_at IS NULL
     * for the current user only. Returns the number of archived rows.
     */
    suspend fun archiveCompletedTasks(): Result<Int>
}
