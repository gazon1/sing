package com.singularity.todo.feature.archive

import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.toTask
import com.singularity.todo.core.sync.SyncRepository
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
    private val syncRepository: SyncRepository,
) {
    /**
     * Archives tasks with completed_at != null AND archived_at IS NULL
     * for the current user only. Returns the number of archived rows.
     *
     * Each archived task is pushed to the sync outbox. Without that, a bulk
     * archive would change N rows locally and sync none of them — the server
     * keeps the un-archived copy and the next pull restores every task, which is
     * the same resurrection the single-task delete path had. The rows are diffed
     * before and after so only newly-archived tasks are pushed, not the whole
     * trash.
     */
    suspend fun archiveCompletedTasks(): Result<Int> = runCatching {
        val uid = currentUser.scopedUserId.value.value
        val before = taskDao.getTrashForUser(uid).map { it.id }.toSet()
        val archived = taskDao.archiveCompletedForUser(clock.now().toEpochMilliseconds(), uid)
        if (archived > 0) {
            taskDao.getTrashForUser(uid)
                .filter { it.id !in before }
                .forEach { syncRepository.enqueue(it.toTask()) }
        }
        archived
    }
}
