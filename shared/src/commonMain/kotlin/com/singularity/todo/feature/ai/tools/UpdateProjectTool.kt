package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.projects.ProjectsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class UpdateProjectInput(
    val projectId: String,
    val name: String? = null,
    val color: Int? = null,
    val icon: String? = null,
    val description: String? = null,
)

@Serializable
data class UpdateProjectOutput(
    val projectId: String,
    val updated: Boolean,
)

class UpdateProjectTool(
    private val projectsRepository: ProjectsRepository,
    private val clock: Clock,
) : SimpleTool<UpdateProjectInput>(TypeToken.of(UpdateProjectInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: UpdateProjectInput): String {
        val existing = projectsRepository.watchProject(ProjectId(args.projectId)).first()
            ?: return Json.encodeToString(
                UpdateProjectOutput.serializer(),
                UpdateProjectOutput(args.projectId, false),
            )

        val updated = existing.copy(
            name = args.name ?: existing.name,
            color = args.color ?: existing.color,
            icon = args.icon ?: existing.icon,
            description = args.description ?: existing.description,
            updatedAt = clock.now(),
        )
        projectsRepository.update(updated)
        return Json.encodeToString(
            UpdateProjectOutput.serializer(),
            UpdateProjectOutput(args.projectId, true),
        )
    }

    companion object {
        const val NAME = "update_project"
        const val DESCRIPTION = "Updates an existing project."
    }
}
