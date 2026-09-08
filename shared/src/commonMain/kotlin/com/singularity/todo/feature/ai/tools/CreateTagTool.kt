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
    /** ARGB integer color. Prefer [colorHex] for cross-client MCP callers. */
    val color: Int = 0xFF9E9E9E.toInt(), // ARGB grey default
    /**
     * Optional hex-string override for [color]. Accepted forms:
     *  - `"#RRGGBB"` (alpha assumed 0xFF)
     *  - `"#AARRGGBB"`
     *  - 6 / 8 hex digits without leading `#`
     * If provided, takes precedence over [color].
     */
    val colorHex: String? = null,
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
        val finalColor = parseColor(args.colorHex, defaultColor = args.color)
        val tag = Tag(
            id = tagId,
            name = args.name,
            color = finalColor,
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
