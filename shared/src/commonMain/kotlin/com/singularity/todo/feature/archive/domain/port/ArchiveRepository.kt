package com.singularity.todo.feature.archive.domain.port

import com.singularity.todo.core.refresh.CompletedTaskArchiver

/**
 * Port for bulk-archiving completed tasks.
 * Platform implementation: [com.singularity.todo.feature.archive.data.TaskDaoArchiveRepositoryImpl].
 *
 * Extends [CompletedTaskArchiver] — the narrow contract the core's refresh
 * run declares. The core does not take a feature's port; the feature's port
 * inherits the core's one, so the dependency points the way the architecture
 * requires and the implementation does not change by a line.
 */
interface ArchiveRepository : CompletedTaskArchiver {
    /**
     * Archives tasks with completed_at != null AND archived_at IS NULL
     * for the current user only. Returns the number of archived rows.
     */
    override suspend fun archiveCompletedTasks(): Result<Int>
}
