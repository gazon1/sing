package com.singularity.todo.feature.tags

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.platform.Clock
import kotlinx.coroutines.flow.Flow

class GetTagsUseCase(private val repo: TagsRepository) {
    operator fun invoke(userId: String): Flow<List<Tag>> = repo.watchTags(userId)
}

class GetTagUseCase(private val repo: TagsRepository) {
    operator fun invoke(id: TagId): Flow<Tag?> = repo.watchTag(id)
}

class CreateTagUseCase(private val repo: TagsRepository, private val clock: Clock) {
    suspend operator fun invoke(input: CreateTagInput): Result<TagId> = runCatchingResult {
        require(input.name.isNotBlank()) { throw AppError.Validation("Name cannot be blank") }
        val now = clock.now()
        val tag = Tag(
            id = TagId.generate(),
            name = input.name.trim(),
            color = input.color,
            createdAt = now,
            updatedAt = now,
            userId = input.userId
        )
        repo.create(tag).getOrThrow()
        tag.id
    }
}

class UpdateTagUseCase(private val repo: TagsRepository, private val clock: Clock) {
    suspend operator fun invoke(tag: Tag): Result<Unit> = runCatchingResult {
        repo.update(tag.copy(updatedAt = clock.now())).getOrThrow()
    }
}

class DeleteTagUseCase(private val repo: TagsRepository) {
    suspend operator fun invoke(id: TagId): Result<Unit> = runCatchingResult {
        repo.delete(id).getOrThrow()
    }
}
