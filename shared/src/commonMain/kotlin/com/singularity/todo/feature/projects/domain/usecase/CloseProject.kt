package com.singularity.todo.feature.projects.domain.usecase

import com.singularity.todo.core.database.UnitOfWork
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
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
 * ## Why the cascade is one transaction
 *
 * The cascade writes several rows: the open tasks, then the project. A failure
 * between them would leave half the work closed and the project open — a state that
 * matches neither what the user asked for nor what the app promised. So the writes
 * share one transaction ([UnitOfWork]): either the project and its tasks are closed
 * together, or nothing is.
 *
 * ## Why the reads are targeted
 *
 * The cascade used to read the whole task list — every task the user has — to
 * answer "is this task blocked". The answer only ever concerned the project's own
 * tasks, so the read now asks about those tasks only: the project's tasks, and the
 * prerequisites of those tasks. The answer is the same; the cost is not.
 *
 * ## Why this is a use case and not a view-model branch
 *
 * The rule — "a project with a blocked task cannot be closed without a
 * confirmation" — is a business rule, and a business rule that lives in a view
 * model is a rule the next caller has to re-implement. Every caller that closes a
 * project gets the same answer.
 *
 * @param projectRepo the project store.
 * @param taskRepo the task store, read for the project's tasks and their
 *   prerequisites.
 * @param clock the moment the project and its tasks are closed.
 * @param unitOfWork the transaction the cascade's writes share.
 */
class CloseProjectUseCase(
    private val projectRepo: ProjectsRepository,
    private val taskRepo: TaskRepository,
    private val clock: Clock,
    private val unitOfWork: UnitOfWork,
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
            // The project's own tasks, not the whole task list: a question about
            // ten tasks does not need an answer about ten thousand.
            val projectTasks = taskRepo.observeByFilter(TaskFilter.ByProject(projectId)).first()
            val openTasks = projectTasks.filter { it.completedAt == null }

            // The prerequisites of those tasks, resolved by the database rather
            // than by walking the task list here.
            val blockedIds = taskRepo.blockedTaskIds(openTasks.map { it.id })
            val blocked = openTasks.filter { it.id in blockedIds }
            if (blocked.isNotEmpty() && !force) {
                return@runCatchingCancellable ProjectCloseOutcome.NeedsConfirmation(
                    blockedTasks = blocked,
                    openTaskCount = openTasks.size,
                )
            }

            val now = clock.now()
            unitOfWork.write {
                openTasks.forEach { task ->
                    // `completedAt` directly, and not the recurring-task path: a
                    // project that is being closed does not schedule another
                    // occurrence of its work for tomorrow.
                    val completed = taskRepo.complete(task.id, force = true).getOrThrow()
                    check(completed) { "Task ${task.id} could not be closed" }
                }
                projectRepo.close(projectId, now.toEpochMilliseconds()).getOrThrow()
            }
            ProjectCloseOutcome.Closed(projectId, openTasks.size)
        }
}
