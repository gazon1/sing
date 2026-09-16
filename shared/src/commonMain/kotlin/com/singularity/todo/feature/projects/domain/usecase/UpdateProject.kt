package com.singularity.todo.feature.projects.domain.usecase

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository

// Keep: has domain timestamp update
class UpdateProjectUseCase(private val repo: ProjectsRepository, private val clock: Clock) {
    /** Full-entity update. */
    suspend operator fun invoke(project: Project): Result<Unit> = runCatchingResult {
        repo.update(project.copy(updatedAt = clock.now())).getOrThrow()
    }

    /**
     * Read-modify-write update.
     * Enables atomic partial updates without a prior read in the caller.
     */
    suspend operator fun invoke(id: ProjectId, transform: (Project) -> Project): Result<Unit> = runCatchingResult {
        val current = repo.getById(id)
            ?: throw AppError.NotFound("Project $id not found")
        val updated = transform(current).copy(updatedAt = clock.now())
        repo.update(updated).getOrThrow()
    }
}
