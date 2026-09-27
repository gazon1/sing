package com.singularity.todo.feature.tags.domain.usecase

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.domain.model.CreateTagGroupInput
import com.singularity.todo.feature.tags.domain.model.TagGroup
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import com.singularity.todo.feature.tags.domain.model.UpdateTagGroupInput
import com.singularity.todo.feature.tags.domain.port.TagGroupRepository
import kotlin.time.Clock

class CreateTagGroupUseCase(
    private val repo: TagGroupRepository,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) {
    suspend operator fun invoke(input: CreateTagGroupInput): Result<TagGroup> = runCatchingResult {
        require(input.name.isNotBlank()) { AppError.Validation("Name cannot be blank") }
        require(input.color != 0) { AppError.Validation("Color must be set") }
        repo.create(input).getOrThrow()
    }
}

class UpdateTagGroupUseCase(private val repo: TagGroupRepository, private val clock: Clock) {
    suspend operator fun invoke(input: UpdateTagGroupInput): Result<Unit> = runCatchingResult {
        require(input.name.isNotBlank()) { AppError.Validation("Name cannot be blank") }
        repo.update(input).getOrThrow()
    }
}

class DeleteTagGroupUseCase(private val repo: TagGroupRepository) {
    suspend operator fun invoke(id: TagGroupId): Result<Unit> = runCatchingResult {
        repo.delete(id).getOrThrow()
    }
}

class SetProjectInheritedGroupsUseCase(private val repo: TagGroupRepository) {
    suspend operator fun invoke(projectId: ProjectId, groupIds: Set<TagGroupId>): Result<Unit> =
        repo.setInheritedForProject(projectId, groupIds)
}
