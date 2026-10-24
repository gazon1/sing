package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.ai.ToolOutputJson
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposedProject
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.projects.domain.model.ProjectId
import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
data class CreateProjectInput(
    val name: String,
    /** ARGB integer color. Prefer [colorHex] for cross-client MCP callers. */
    val color: Int = 0xFF2196F3.toInt(), // ARGB blue default
    val icon: String? = null,
    val description: String? = null,
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
 * @property proposalCreated whether a proposal was stored. There is no project id
 *   here: nothing was created, and an id would name a project that does not exist.
 */
@Serializable
data class CreateProjectOutput(
    val proposalCreated: Boolean,
    val proposalId: String,
    val name: String,
    val error: String? = null,
)

/**
 * Proposes a new project, and lets the user decide whether it should exist.
 *
 * A project the assistant created on its own is a project the user never saw —
 * and a project is a container for work, so an unwanted one is worse than an
 * unwanted task. The proposal holds it until the user says yes.
 */
class CreateProjectTool(
    private val proposals: ProposalRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : SimpleTool<CreateProjectInput>(TypeToken.of(CreateProjectInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: CreateProjectInput): String {
        val name = args.name.trim()
        if (name.isEmpty()) {
            return failure("", "A project needs a name.")
        }

        // The id the project will have if the user says yes.
        val projectId = ProjectId.generate()
        val kind = ProposalItemKind.CreateProject(
            ProposedProject(
                name = name,
                description = args.description,
                color = parseColor(args.colorHex, defaultColor = args.color),
                icon = args.icon,
            ),
        )

        val result = proposeChange(
            proposals = proposals,
            userId = currentUser.scopedUserId.value,
            clock = clock,
            items = proposalItems(listOf(kind), projectId.value),
            targetId = projectId.value,
        )

        return ToolOutputJson.encodeToString(
            CreateProjectOutput.serializer(),
            CreateProjectOutput(
                proposalCreated = result.isSuccess,
                proposalId = result.getOrNull()?.id?.value.orEmpty(),
                name = name,
                error = result.exceptionOrNull()?.message,
            ),
        )
    }

    private fun failure(name: String, message: String): String = ToolOutputJson.encodeToString(
        CreateProjectOutput.serializer(),
        CreateProjectOutput(proposalCreated = false, proposalId = "", name = name, error = message),
    )

    companion object {
        const val NAME = "create_project"

        /**
         * The description the model reads.
         *
         * It says the project is proposed rather than created, because a model
         * that believes it has created a project will say so to the user.
         */
        const val DESCRIPTION =
            "Proposes a new project. The project is not created yet — the user sees it and decides. " +
                "Say that it has been proposed, not that it has been created. " +
                "colorHex is #RRGGBB or #AARRGGBB."
    }
}
