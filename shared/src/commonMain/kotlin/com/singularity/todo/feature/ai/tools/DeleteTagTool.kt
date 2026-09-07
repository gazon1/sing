package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DeleteTagInput(val tagId: String)

@Serializable
data class DeleteTagOutput(
    val tagId: String,
    val deleted: Boolean,
    val error: String? = null,
)

class DeleteTagTool(private val tagsRepository: TagsRepository) :
    SimpleTool<DeleteTagInput>(TypeToken.of(DeleteTagInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: DeleteTagInput): String {
        val result = tagsRepository.delete(TagId.fromString(args.tagId))
        return Json.encodeToString(
            DeleteTagOutput.serializer(),
            DeleteTagOutput(
                tagId = args.tagId,
                deleted = result.isSuccess,
                error = result.exceptionOrNull()?.message,
            ),
        )
    }

    companion object {
        const val NAME = "delete_tag"
        const val DESCRIPTION = "Soft-deletes a tag by its ID."
    }
}
