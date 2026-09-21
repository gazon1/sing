package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import kotlinx.serialization.Serializable

@Serializable
data class GetProjectInput(val projectId: String)

@Serializable
data class GetProjectOutput(val id: String, val name: String, val description: String?)

class GetProjectTool(private val projectsRepository: ProjectsRepository) :
    SimpleTool<GetProjectInput>(TypeToken.of(GetProjectInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: GetProjectInput): String {
        val project = projectsRepository.get(ProjectId(args.projectId))
        val output = if (project != null) {
            GetProjectOutput(project.id.value, project.name, project.description)
        } else {
            GetProjectOutput(args.projectId, "(not found)", null)
        }
        return kotlinx.serialization.json.Json.encodeToString(GetProjectOutput.serializer(), output)
    }

    companion object {
        const val NAME = "get_project"
        const val DESCRIPTION = "Fetch a single project by its ID."
    }
}
