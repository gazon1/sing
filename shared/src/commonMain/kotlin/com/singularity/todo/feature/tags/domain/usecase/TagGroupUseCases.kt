package com.singularity.todo.feature.tags.domain.usecase

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tags.domain.model.CreateTagGroupInput
import com.singularity.todo.feature.tags.domain.model.TagGroup
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import com.singularity.todo.feature.tags.domain.port.TagGroupRepository
import kotlin.time.Clock

class CreateTagGroupUseCase(
    private val repo: TagGroupRepository,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) {
    suspend operator fun invoke(input: CreateTagGroupInput): Result<TagGroup> = runCatchingResult {
        // `require(cond) { AppError.Validation(...) }` reads like it throws the AppError and
        // throws nothing of the sort: the lambda is `lazyMessage: () -> Any`, so the AppError
        // is constructed, stringified into an IllegalArgumentException's message, and
        // discarded — subtype, code and cause all gone. runCatchingResult then classifies the
        // IllegalArgumentException as Unknown, so a blank name and a transparent colour both
        // landed in the same untyped bucket as every other unexpected throw. Throwing the
        // AppError directly is what makes the two failures distinguishable at all.
        if (input.name.isBlank()) {
            throw AppError.Validation("Name cannot be blank", code = "tag_group.name.blank")
        }
        if (input.color == 0) {
            throw AppError.Validation("Color must be set", code = "tag_group.color.unset")
        }
        repo.create(input).getOrThrow()
    }
}

class DeleteTagGroupUseCase(private val repo: TagGroupRepository) {
    suspend operator fun invoke(id: TagGroupId): Result<Unit> = runCatchingResult {
        repo.delete(id).getOrThrow()
    }
}
