package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CreateTagInput(
    val name: String,
    val color: Int = 0xFF9E9E9E.toInt(), // ARGB grey default
)

@Serializable
data class CreateTagOutput(
    val tagId: String,
    val name: String,
)

class CreateTagTool(
    private val tagsRepository: TagsRepository,
    private val profileAwareCurrentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : SimpleTool<CreateTagInput>(TypeToken.of(CreateTagInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: CreateTagInput): String {
        val now = clock.now()
        val tagId = TagId.generate()
        val userId = profileAwareCurrentUser.scopedUserId.value.value
        val tag = Tag(
            id = tagId,
            name = args.name,
            color = args.color,
            createdAt = now,
            updatedAt = now,
            userId = userId,
        )
        tagsRepository.create(tag)
        return Json.encodeToString(
            CreateTagOutput.serializer(),
            CreateTagOutput(tagId.value, tag.name),
        )
    }

    companion object {
        const val NAME = "create_tag"
        const val DESCRIPTION = "Creates a new tag."
    }
}
