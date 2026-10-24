package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.ai.ToolOutputJson
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProjectValue
import com.singularity.todo.feature.proposals.domain.port.ProposalRepository
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
data class UpdateProjectInput(
    val projectId: String,
    val name: String? = null,
    val color: Int? = null,
    val icon: String? = null,
    val description: String? = null,
)

/**
 * What came of the request.
 *
 * @property proposalCreated whether a proposal was stored. There is no "updated"
 *   here: nothing was updated, and a flag that says otherwise would be a lie the
 *   model would repeat to the user.
 */
@Serializable
data class UpdateProjectOutput(
    val proposalCreated: Boolean,
    val proposalId: String,
    val projectId: String,
    val error: String? = null,
)

/**
 * Proposes a change to a project, and lets the user decide.
 *
 * The change is proposed per field, so the user reads "Rename the project" and
 * "Recolor the project" rather than a project they have to compare with the one
 * they already have.
 */
class UpdateProjectTool(
    private val proposals: ProposalRepository,
    private val projectsRepository: ProjectsRepository,
    private val currentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : SimpleTool<UpdateProjectInput>(TypeToken.of(UpdateProjectInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: UpdateProjectInput): String {
        val existing = projectsRepository.get(ProjectId(args.projectId))
            ?: return failure(args.projectId, "There is no project with id \"${args.projectId}\".")

        val kinds = changes(args, existing)
        if (kinds.isEmpty()) {
            return failure(args.projectId, "The project already has these values — there is nothing to change.")
        }

        val result = proposeChange(
            proposals = proposals,
            userId = currentUser.scopedUserId.value,
            clock = clock,
            items = proposalItems(kinds, args.projectId),
            targetId = args.projectId,
        )

        return ToolOutputJson.encodeToString(
            UpdateProjectOutput.serializer(),
            UpdateProjectOutput(
                proposalCreated = result.isSuccess,
                proposalId = result.getOrNull()?.id?.value.orEmpty(),
                projectId = args.projectId,
                error = result.exceptionOrNull()?.message,
            ),
        )
    }

    /** The changes the request asks for: one item per field that actually differs. */
    private fun changes(args: UpdateProjectInput, existing: Project): List<ProposalItemKind> {
        val kinds = mutableListOf<ProposalItemKind>()

        args.name?.let { value ->
            if (value.trim() != existing.name) {
                kinds += ProposalItemKind.SetProjectField(ProjectValue.Name(value))
            }
        }

        args.description?.let { value ->
            if (value != existing.description) {
                kinds += ProposalItemKind.SetProjectField(ProjectValue.Description(value))
            }
        }

        args.color?.let { value ->
            if (value != existing.color) {
                kinds += ProposalItemKind.SetProjectField(ProjectValue.Color(value))
            }
        }

        args.icon?.let { value ->
            if (value != existing.icon) {
                kinds += ProposalItemKind.SetProjectField(ProjectValue.Icon(value))
            }
        }

        return kinds
    }

    private fun failure(projectId: String, message: String): String = ToolOutputJson.encodeToString(
        UpdateProjectOutput.serializer(),
        UpdateProjectOutput(proposalCreated = false, proposalId = "", projectId = projectId, error = message),
    )

    companion object {
        const val NAME = "update_project"

        /**
         * The description the model reads.
         *
         * It says the change is proposed rather than performed, because a model
         * that believes it has changed the project will say so to the user.
         */
        const val DESCRIPTION =
            "Proposes a change to a project. The change is not applied yet — the user sees it and " +
                "decides. Say that it has been proposed, not that it has been done. " +
                "Pass only the fields to change; a field left out is not touched."
    }
}
