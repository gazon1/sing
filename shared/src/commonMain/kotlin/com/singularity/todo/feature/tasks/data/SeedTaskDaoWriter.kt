package com.singularity.todo.feature.tasks.data

import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.core.database.UnitOfWork
import com.singularity.todo.core.repository.assertCanWrite
import com.singularity.todo.core.seed.SeedTaskWriter
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task

/**
 * The seed's batch write: one statement per row, one transaction, no patch per row.
 *
 * ## Why this is not the repository's `create`
 *
 * `create` costs, per row, an upsert, an outgoing-links write, a fresh-row read, a
 * sync-outbox write, a shadow write and a JSON patch — measured at ~30 ms a row on a
 * realme RMX3938, so a thousand-row fixture took ~30 s. That is longer than the largest
 * wait the Maestro layer has (`config/docs/ui-wait-budgets.txt`), so the flow could not
 * wait for the data it had asked for.
 *
 * The patch is not waste to trim from `create`: it is how the server learns about a
 * user's edit, which is exactly why the ordinary create is expensive. A seeded row is
 * the other kind of row — the flow that seeded it asserts on it on this device, in this
 * run, and a debug build has no server to tell. See ADR
 * `2026-11-24-the-seed-writes-its-rows-without-a-patch`.
 *
 * ## What is kept
 *
 * The profile scope, the cross-user guard, the tags and follow-up edges the task
 * carries, and the transaction — a bulk that fails halfway leaves nothing behind.
 * Ordinary `create` is untouched: a user's write still writes its patch, in the same
 * transaction.
 *
 * The row mapper is the repository's own, widened to `internal` rather than copied:
 * `outgoing_links` is *derived* from the description, and two derivations would be two
 * truths about the same row.
 */
class SeedTaskDaoWriter(
    private val taskDao: TaskDao,
    private val currentUser: ProfileAwareCurrentUser,
    private val unitOfWork: UnitOfWork,
) : SeedTaskWriter {
    override suspend fun writeFixtures(tasks: List<Task>): List<Task> =
        unitOfWork.write {
            val uid = currentUser.scopedUserId.value
            tasks.map { item ->
                currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
                val toInsert = item.copy(userId = uid)
                // The links travel with the row in this one statement, rather than in the
                // second UPDATE per row that `create` spends them on.
                taskDao.upsert(toInsert.toEntity())
                toInsert.tags.forEach { tagId ->
                    taskDao.upsertTagCrossRefForUser(toInsert.id.value, tagId.value, uid.value)
                }
                toInsert.followsUp.forEach { depId ->
                    taskDao.upsertDependencyForUser(
                        toInsert.id.value,
                        depId.value,
                        DependencyVerb.FOLLOWS_UP.name,
                        uid.value,
                    )
                }
                toInsert
            }
        }
}
