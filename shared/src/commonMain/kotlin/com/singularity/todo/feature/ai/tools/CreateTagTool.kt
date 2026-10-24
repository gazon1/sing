package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.ai.ToolOutputJson
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposedTag
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.tags.TagId
import kotlinx.serialization.Serializable
import kotlin.time.Clock

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

/**
 * What came of the request.
 *
 * @property proposalCreated whether a proposal was stored. There is no tag id here:
 *   nothing was created, and an id would name a tag that does not exist.
 */
@Serializable
data class CreateTagOutput(
    val proposalCreated: Boolean,
    val proposalId: String,
    val name: String,
    val error: String? = null,
)

/**
 * Proposes a new tag, and lets the user decide whether it should exist.
 *
 * This was the last tool that wrote the user's data on its own. A tag the
 * assistant created without asking is a tag the user did not ask for, sitting in
 * their tag list with a name they did not choose — and every other write goes
 * through the proposal, so this one did too.
 */
class CreateTagTool(
    private val proposals: ProposalRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : SimpleTool<CreateTagInput>(TypeToken.of(CreateTagInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: CreateTagInput): String {
        val name = args.name.trim()
        if (name.isEmpty()) {
            return failure("", "A tag needs a name.")
        }

        // The id the tag will have if the user says yes.
        val tagId = TagId.generate()
        val kind = ProposalItemKind.CreateTag(
            ProposedTag(
                name = name,
                color = parseColor(args.colorHex, defaultColor = args.color),
            ),
        )

        val result = proposeChange(
            proposals = proposals,
            userId = currentUser.scopedUserId.value,
            clock = clock,
            items = proposalItems(listOf(kind), tagId.value),
            targetId = tagId.value,
        )

        return ToolOutputJson.encodeToString(
            CreateTagOutput.serializer(),
            CreateTagOutput(
                proposalCreated = result.isSuccess,
                proposalId = result.getOrNull()?.id?.value.orEmpty(),
                name = name,
                error = result.exceptionOrNull()?.message,
            ),
        )
    }

    private fun failure(name: String, message: String): String = ToolOutputJson.encodeToString(
        CreateTagOutput.serializer(),
        CreateTagOutput(proposalCreated = false, proposalId = "", name = name, error = message),
    )

    companion object {
        const val NAME = "create_tag"

        /**
         * The description the model reads.
         *
         * It says the tag is proposed rather than created, because a model that
         * believes it has created a tag will say so to the user.
         */
        const val DESCRIPTION =
            "Proposes a new tag. The tag is not created yet — the user sees it and decides. " +
                "Say that it has been proposed, not that it has been created. " +
                "colorHex is #RRGGBB or #AARRGGBB."
    }
}
