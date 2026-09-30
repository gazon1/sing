package com.singularity.todo.feature.tags.domain.usecase

import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.feature.tags.CreateTagInput
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tags.domain.TagDomain
import kotlin.time.Clock

// Keep: has validation (require without throw) + clock + TagId generation
class CreateTagUseCase(private val repo: TagsRepository, private val clock: Clock) {
    suspend operator fun invoke(input: CreateTagInput): Result<TagId> = runCatchingResult {
        TagDomain.validate(input.name, input.color)?.let { throw it }
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

/**
 * Updates an existing tag — used by rename and by colour changes.
 *
 * The id, [Tag.createdAt] and [Tag.userId] of [tag] are carried through
 * untouched, so every task referencing the tag keeps its link. Only the name,
 * colour and [Tag.updatedAt] change. [updatedAt] is stamped from the injected
 * clock rather than accepted from the caller.
 */
class UpdateTagUseCase(private val repo: TagsRepository, private val clock: Clock) {
    suspend operator fun invoke(tag: Tag): Result<Unit> = runCatchingResult {
        TagDomain.validate(tag.name, tag.color)?.let { throw it }
        repo.update(tag.copy(name = tag.name.trim(), updatedAt = clock.now())).getOrThrow()
    }
}
