package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.core.error.runCatchingCancellable

/**
 * Single injection point for bulk task mutation operations.
 * Individual mutations (delete/toggle/togglePin) are called directly on [TaskRepository].
 *
 * Bulk operations enforce atomicity: fail-fast if any ID doesn't exist,
 * before mutating anything. This lives here so VMs stay thin.
 *
 * @param repo The task repository to delegate to.
 */
open class TaskMutationsUseCase(protected val repo: TaskRepository) {

    /**
     * Completes every task in [ids] — or none of them.
     *
     * ## Why a bulk close is gated like a single one
     *
     * A bulk close is a close. Closing one task whose prerequisite is open
     * asks the user; closing ten of them at once must not be the way around
     * the question. So the selection is checked before anything is written,
     * and a blocked task in it stops the whole operation with the names of the
     * tasks in the way — nothing is written, so the user can answer and try
     * again.
     *
     * The write refuses as well, on its own: a prerequisite can be reopened
     * between the check and the write, and the write is the one that has the
     * last word.
     */
    suspend fun bulkComplete(ids: List<TaskId>): Result<Unit> = runCatchingCancellable {
        // Atomic: fail-fast if any ID doesn't exist, before mutating anything.
        ids.forEach { id ->
            if (!repo.exists(id)) return Result.failure(IllegalArgumentException("Task $id not found"))
        }
        val blocked = repo.blockedTaskIds(ids)
        if (blocked.isNotEmpty()) {
            return Result.failure(BlockedTasksException(blocked))
        }
        ids.forEach { id ->
            val completed = repo.complete(id).getOrThrow()
            if (!completed) {
                // The write refused although the check said nothing was in the
                // way: a prerequisite was reopened in between.
                throw BlockedTasksException(setOf(id))
            }
        }
    }

    suspend fun bulkDelete(ids: List<TaskId>): Result<Unit> = runCatchingCancellable {
        ids.forEach { id ->
            if (!repo.exists(id)) return Result.failure(IllegalArgumentException("Task $id not found"))
        }
        ids.forEach { repo.softDelete(it).getOrThrow() }
    }
}

/**
 * The tasks that were in the way of a bulk close.
 *
 * The ids are enough to point at the tasks; the caller that shows the message
 * reads their titles.
 */
class BlockedTasksException(val blockedTaskIds: Set<TaskId>) :
    IllegalStateException("These tasks have unfinished prerequisites: ${blockedTaskIds.joinToString()}")
