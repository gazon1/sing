package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.projects.usecase.DeleteProjectUseCase
import com.singularity.todo.core.ids.UserId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DeleteProjectInput(val projectId: String)

@Serializable
data class DeleteProjectOutput(
    val projectId: String,
    val deleted: Boolean,
    val error: String? = null,
)

class DeleteProjectTool(
    private val deleteProject: DeleteProjectUseCase,
    private val currentUser: ProfileAwareCurrentUser,
) : SimpleTool<DeleteProjectInput>(TypeToken.of(DeleteProjectInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: DeleteProjectInput): String {
        val userId = currentUser.scopedUserId.value.value
        val result = deleteProject(ProjectId.fromString(args.projectId), UserId(userId))
        return Json.encodeToString(
            DeleteProjectOutput.serializer(),
            DeleteProjectOutput(
                projectId = args.projectId,
                deleted = result.isSuccess,
                error = result.exceptionOrNull()?.message,
            ),
        )
    }

    companion object {
        const val NAME = "delete_project"
        const val DESCRIPTION = "Soft-deletes (archives) a project by its ID."
    }
}
