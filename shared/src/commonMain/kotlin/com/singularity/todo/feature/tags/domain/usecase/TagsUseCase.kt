package com.singularity.todo.feature.tags.domain.usecase

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tags.CreateTagInput
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository

// Keep: has validation (require without throw) + clock + TagId generation
class CreateTagUseCase(private val repo: TagsRepository, private val clock: Clock) {
    suspend operator fun invoke(input: CreateTagInput): Result<TagId> = runCatchingResult {
        require(input.name.isNotBlank()) { AppError.Validation("Name cannot be blank") }
        val now = clock.now()
        val tag = Tag(
            id = TagId.generate(),
            name = input.name.trim(),
            color = input.color,
            createdAt = now,
            updatedAt = now,
            userId = input.userId,
            groupId = input.groupId,
        )
        repo.create(tag).getOrThrow()
        tag.id
    }
}

// Keep: has domain timestamp update
class UpdateTagUseCase(private val repo: TagsRepository, private val clock: Clock) {
    suspend operator fun invoke(tag: Tag): Result<Unit> = runCatchingResult {
        repo.update(tag.copy(updatedAt = clock.now())).getOrThrow()
    }
}
