package com.singularity.todo.feature.projects.usecase

import com.singularity.todo.core.error.AppError
import com.singularity.todo.feature.projects.ProjectsRepository
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.core.ids.UserId
import kotlinx.coroutines.flow.first

/**
 * Deletes a project.
 *
 * Business rule: a project with active tasks cannot be deleted.
 * This validation lives in the use case rather than the ViewModel so it can be
 * reused by any caller (shell, AI tools, etc.).
 */
class DeleteProjectUseCase(
    private val projectRepo: ProjectsRepository,
    private val taskRepo: TaskRepository,
) {
    suspend operator fun invoke(id: ProjectId, userId: UserId): Result<Unit> = runCatching {
        // Guard: reject if project has tasks
        val tasks = taskRepo.watchTasks(userId, TaskFilter.ByProject(id)).first()
        if (tasks.isNotEmpty()) {
            throw AppError.Validation(
                "Cannot delete a project that has tasks. Archive or delete the tasks first."
            )
        }
        projectRepo.delete(id).getOrThrow()
    }
}
