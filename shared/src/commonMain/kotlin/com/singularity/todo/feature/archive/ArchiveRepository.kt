package com.singularity.todo.feature.archive

import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlin.time.Clock

/**
 * Bulk-archive completed tasks. The repository owns the side effect;
 * ViewModels only call [archiveCompletedTasks].
 *
 * Scoped to the active profile: the previous form issued a single global
 * `UPDATE` that archived completed tasks belonging to *every* user.
 */
class TaskDaoArchiveRepository(
    private val taskDao: TaskDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) {
    /**
     * Archives tasks with completed_at != null AND archived_at IS NULL
     * for the current user only. Returns the number of archived rows.
     */
    suspend fun archiveCompletedTasks(): Result<Int> = runCatching {
        taskDao.archiveCompletedForUser(clock.now().toEpochMilliseconds(), currentUser.scopedUserId.value.value)
    }
}
