package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.firstOrNull
import kotlin.time.Clock

/**
 * Taking a task back into work, and opening the project it belongs to.
 *
 * ## Why this is a use case
 *
 * The closing direction needs no use case: the write itself refuses while a
 * prerequisite is open ([TaskRepository.complete]), and the surface asks when
 * it refuses. That is a rule the data layer owns, and a use case in front of
 * it would only repeat it.
 *
 * The *opening* direction needs one, because it spans two features. A closed
 * project leaves the project list, but its tasks can still be picked up in the
 * agenda. Without a rule the task would come back alone, and the project it
 * belongs to would stay invisible — the task would belong to nothing.
 * Reopening the project is the mirror of the close cascade, and it is the same
 * shape: the work came back, so the project is not finished.
 *
 * ## Why the project is read, not watched
 *
 * The project is read once, at the moment the task comes back. A closed
 * project is a fact, not a stream: the write either finds it closed and opens
 * it, or finds it open and leaves it alone.
 *
 * @param taskRepo the task store.
 * @param projectRepo the project store, read to find out whether the project a
 *   task belongs to is closed.
 * @param clock the moment the project is opened again.
 */
class TaskReopenUseCase(
    private val taskRepo: TaskRepository,
    private val projectRepo: ProjectsRepository,
    private val clock: Clock,
) {

    /**
     * Takes the task back into work, and opens its project if that project was
     * closed.
     *
     * Never gated: the direction of the change is the user's business.
     *
     * @param taskId the task to reopen.
     */
    suspend fun reopen(taskId: TaskId): Result<Unit> = runCatchingCancellable {
        val task = taskRepo.get(taskId)
        taskRepo.reopen(taskId).getOrThrow()
        val projectId = task?.projectId ?: return@runCatchingCancellable
        // The project is only opened when it is actually closed: reopening an
        // open project would be a write that says nothing.
        val project = projectRepo.observeProject(projectId).firstOrNull()
        if (project?.closedAt != null) {
            projectRepo.reopen(projectId, clock.now().toEpochMilliseconds()).getOrThrow()
        }
    }
}
