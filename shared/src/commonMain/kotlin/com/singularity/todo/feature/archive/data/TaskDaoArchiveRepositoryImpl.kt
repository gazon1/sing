package com.singularity.todo.feature.archive.data

import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.feature.archive.domain.port.ArchiveRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.freshTasks
import kotlin.time.Clock
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.database.UnitOfWork

/**
 * Bulk-archive completed tasks. The repository owns the side effect;
 * ViewModels only call [archiveCompletedTasks].
 *
 * Scoped to the active profile: the previous form issued a single global
 * `UPDATE` that archived completed tasks belonging to *every* user.
 */
class TaskDaoArchiveRepositoryImpl(
    private val taskDao: TaskDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
    private val syncRepository: SyncRepository,
    private val unitOfWork: UnitOfWork,
) : ArchiveRepository {
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
    override suspend fun archiveCompletedTasks(): Result<Int> = runCatchingCancellable {
        unitOfWork.write {
            val uid = currentUser.scopedUserId.value
            val before = taskDao.getTrashForUser(uid.value).map { it.id }.toSet()
            val archived = taskDao.archiveCompletedForUser(clock.now().toEpochMilliseconds(), uid.value)
            if (archived > 0) {
                val rows = taskDao.getTrashForUser(uid.value).filter { it.id !in before }
                // The tasks the wire carries are the tasks the database holds,
                // tags and dependencies included. A row on its own carries
                // neither — they live in cross-ref tables — and on the wire an
                // empty tag list is a claim: "the work has no tags". The server
                // would apply it, and every other device would lose the tags on
                // the next pull.
                taskDao.freshTasks(rows, uid)
                    .forEach { task -> syncRepository.enqueue(task) }
            }
            archived
        }
    }
}
