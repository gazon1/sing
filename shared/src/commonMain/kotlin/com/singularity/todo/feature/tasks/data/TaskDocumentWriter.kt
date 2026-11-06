package com.singularity.todo.feature.tasks.data

import co.touchlab.kermit.Logger
import com.singularity.todo.core.database.TaskDao
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDocument
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.DependencyValidator
import kotlinx.coroutines.flow.first

/**
 * Writes the parts of a [TaskDocument] that live outside the row: the tags, the
 * outgoing links implied by the description, and the blocking dependencies.
 *
 * The row itself is written by the repository; this component owns the
 * cross-reference tables and the cycle rule. Keeping the two apart is what
 * stops the repository from being the place where document rules and
 * persistence meet.
 */
class TaskDocumentWriter(
    private val taskDao: TaskDao,
    private val currentUser: ProfileAwareCurrentUser,
    private val dependencyValidator: DependencyValidator,
    private val log: Logger,
) {
    suspend fun writeParts(
        task: Task,
        document: TaskDocument,
    ) {
        val uid = currentUser.scopedUserId.value.value
        document.tags?.let { tags -> writeTags(task.id, tags, uid) }
        // The row's own time, not the clock: a document write must not re-stamp
        // a row whose timestamp is part of the state being written.
        saveOutgoingLinks(task.id, task.description, task.updatedAt.toEpochMilliseconds())
        document.blockedBy?.let { deps -> writeBlockingDependencies(task.id, deps, uid) }
    }

    private suspend fun writeTags(
        id: TaskId,
        tags: List<TagId>,
        uid: String,
    ) {
        val existing = taskDao.getTagIdsForUser(id.value, uid).first().toSet()
        val wanted = tags.map { it.value }.toSet()
        if (existing == wanted) return
        existing.forEach { tagId -> taskDao.removeTagRefForUser(id.value, tagId, uid) }
        wanted.forEach { tagId -> taskDao.upsertTagCrossRefForUser(id.value, tagId, uid) }
    }

    private suspend fun saveOutgoingLinks(
        id: TaskId,
        description: String?,
        updatedAt: Long,
    ) {
        val rows =
            taskDao.setOutgoingLinksForUser(
                id = id.value,
                linksJson = linksJsonOf(description),
                updatedAt = updatedAt,
                userId = currentUser.scopedUserId.value.value,
            )
        require(rows > 0) { "Outgoing links not written for $id — not found or not owned by current user" }
    }

    private suspend fun writeBlockingDependencies(
        id: TaskId,
        blockedBy: Set<TaskId>,
        uid: String,
    ) {
        val desired = blockedBy.map { it.value }.toSet()
        val existing =
            taskDao
                .getBlockingEdgeIdsForUser(id.value, uid, DependencyVerb.BLOCKS.name)
                .first()
                .toSet()
        (existing - desired).forEach { depId ->
            taskDao.removeDependencyForVerb(id.value, depId, DependencyVerb.BLOCKS.name, uid)
        }
        (desired - existing).forEach { depId ->
            val closesCycle =
                dependencyValidator
                    .assertNoCycles(id, setOf(TaskId.fromString(depId)))
                    .isFailure
            if (closesCycle) {
                log.w {
                    "The task ${id.value} of the profile $uid was not made to wait for " +
                        "$depId: the ${DependencyVerb.BLOCKS.name} edge would close a cycle in " +
                        "the local graph. The row and the rest of the document were applied."
                }
                return@forEach
            }
            taskDao.upsertDependencyForUser(id.value, depId, DependencyVerb.BLOCKS.name, uid)
        }
    }
}
