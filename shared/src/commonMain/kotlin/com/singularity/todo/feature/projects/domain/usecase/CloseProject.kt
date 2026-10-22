package com.singularity.todo.feature.projects.domain.usecase

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tasks.domain.logic.activeBlockersByTask
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/**
 * What closing a project did, or what it needs before it can.
 */
sealed interface ProjectCloseOutcome {
    /**
     * The project is closed and so are the tasks that were still open in it.
     *
     * @property closedTaskCount how many tasks were closed by the cascade.
     */
    data class Closed(val projectId: ProjectId, val closedTaskCount: Int) : ProjectCloseOutcome

    /**
     * Some of the project's tasks have unfinished prerequisites, and **nothing was
     * written**.
     *
     * The caller shows the confirmation and calls [CloseProjectUseCase.close] again
     * with `force = true`.
     *
     * @property blockedTasks the tasks that are in the way.
     * @property openTaskCount how many of the project's tasks the cascade would close.
     */
    data class NeedsConfirmation(val blockedTasks: List<Task>, val openTaskCount: Int) : ProjectCloseOutcome
}

/**
 * Closes a project, and the tasks that are still open in it.
 *
 * ## Why the cascade
 *
 * "This project is done" that leaves its tasks live in the agenda is a promise the
 * system does not keep: the work comes back as today's tasks tomorrow. So closing a
 * project closes its still-open direct tasks with it.
 *
 * The cascade stops at direct tasks. A nested project is a position in a hierarchy,
 * not something the parent contains — a parent being finished does not make a child
 * project's work finished.
 *
 * ## Why nothing is written before the confirmation
 *
 * The same rule that governs closing one task governs closing a set of them. A
 * confirmation that arrives after half the work is already done is not a
 * confirmation, it is a report, and the user can no longer act on it. So the check
 * happens before anything is written — the same fail-fast ordering
 * [com.singularity.todo.feature.tasks.domain.usecase.TaskMutationsUseCase.bulkComplete]
 * uses for a bulk operation.
 *
 * ## Why this is a use case and not a view-model branch
 *
 * The rule — "a project with a blocked task cannot be closed without a
 * confirmation" — is a business rule, and a business rule that lives in a view
 * model is a rule the next caller has to re-implement. Every caller that closes a
 * project gets the same answer.
 *
 * @param projectRepo the project store.
 * @param taskRepo the task store, read for the project's tasks and the whole list.
 * @param updateTask the read-modify-write task update, so the cascade does not
 *   rewrite a row it read a while ago.
 * @param clock the moment the project and its tasks are closed.
 */
class CloseProjectUseCase(
    private val projectRepo: ProjectsRepository,
    private val taskRepo: TaskRepository,
    private val updateTask: UpdateTaskUseCase,
    private val clock: Clock,
) {

    /**
     * Closes [projectId] and the tasks that are still open in it.
     *
     * @param projectId the project to close.
     * @param force close even when one of the tasks has an unfinished prerequisite.
     *   The caller passes `true` only after the user has confirmed.
     */
    suspend fun close(projectId: ProjectId, force: Boolean = false): Result<ProjectCloseOutcome> =
        runCatchingCancellable {
            // The whole list, because "is this task blocked" is a question about
            // other tasks — and the same list the blocked badge was computed from,
            // so the badge and the confirmation cannot disagree.
            val allTasks = taskRepo.observeByFilter(TaskFilter.All).first()
            val projectTasks = taskRepo.observeByFilter(TaskFilter.ByProject(projectId)).first()
            val openTasks = projectTasks.filter { it.completedAt == null }
            // One index for the whole list, so the cost is O(n + edges) rather than
            // the quadratic cost of resolving every task's prerequisites on its own.
            val blockersByTask = activeBlockersByTask(allTasks)

            // Fail-fast: every task has to exist before any of them is written.
            openTasks.forEach { task ->
                if (!taskRepo.exists(task.id)) {
                    throw IllegalArgumentException("Task ${task.id} not found")
                }
            }

            val blocked = openTasks.filter { blockersByTask.containsKey(it.id) }
            if (blocked.isNotEmpty() && !force) {
                return@runCatchingCancellable ProjectCloseOutcome.NeedsConfirmation(
                    blockedTasks = blocked,
                    openTaskCount = openTasks.size,
                )
            }

            val now = clock.now()
            openTasks.forEach { task ->
                // `completedAt` directly, and not the recurring-task path: a project
                // that is being closed does not schedule another occurrence of its
                // work for tomorrow.
                updateTask(task.id) { it.copy(completedAt = now) }.getOrThrow()
            }
            projectRepo.close(projectId, now.toEpochMilliseconds()).getOrThrow()
            ProjectCloseOutcome.Closed(projectId, openTasks.size)
        }
}
