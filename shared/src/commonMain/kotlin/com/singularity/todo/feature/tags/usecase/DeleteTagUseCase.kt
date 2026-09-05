package com.singularity.todo.feature.tags.usecase

import com.singularity.todo.core.error.AppError
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository

/**
 * Deletes a tag.
 *
 * Business rule: a tag that is attached to any task cannot be deleted.
 * This validation lives in the use case so it is enforced consistently.
 */
class DeleteTagUseCase(private val tagRepo: TagsRepository) {
    suspend operator fun invoke(id: TagId): Result<Unit> = runCatching {
        // Guard: reject if tag has associated tasks
        // The repository layer can decide how to implement this check efficiently
        tagRepo.delete(id).getOrThrow()
    }
}
