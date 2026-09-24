package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable
data class ListProjectsInput(val limit: Int = 50)

@Serializable
data class ListProjectsOutput(val projects: List<ProjectSummary>)

@Serializable
data class ProjectSummary(
    val id: String,
    val name: String,
    val color: Int,
    val icon: String? = null,
    val description: String? = null,
    val totalCount: Int = 0,
    val completedCount: Int = 0,
)

class ListProjectsTool(private val projectsRepository: ProjectsRepository) :
    SimpleTool<ListProjectsInput>(TypeToken.of(ListProjectsInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: ListProjectsInput): String {
        val rows = projectsRepository.observeProjectsWithCounts().first()
            .take(args.limit)
            .map { row ->
                ProjectSummary(
                    id = row.project.id,
                    name = row.project.name,
                    color = row.project.color,
                    icon = row.project.icon,
                    description = row.project.description,
                    totalCount = row.totalCount,
                    completedCount = row.completedCount,
                )
            }
        return kotlinx.serialization.json.Json.encodeToString(
            ListProjectsOutput.serializer(),
            ListProjectsOutput(rows),
        )
    }

    companion object {
        const val NAME = "list_projects"
        const val DESCRIPTION = "List all projects for the current user, with task counts."
    }
}
