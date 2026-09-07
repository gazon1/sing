package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.Project
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.projects.ProjectsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.datetime.LocalDate

@Serializable
data class CreateProjectInput(
    val name: String,
    val color: Int = 0xFF2196F3.toInt(), // ARGB blue default
    val icon: String? = null,
    val description: String? = null,
)

@Serializable
data class CreateProjectOutput(
    val projectId: String,
    val name: String,
)

class CreateProjectTool(
    private val projectsRepository: ProjectsRepository,
    private val profileAwareCurrentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : SimpleTool<CreateProjectInput>(TypeToken.of(CreateProjectInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: CreateProjectInput): String {
        val now = clock.now()
        val projectId = ProjectId.generate()
        val userId = profileAwareCurrentUser.scopedUserId.value.value
        val project = Project(
            id = projectId,
            name = args.name,
            color = args.color,
            icon = args.icon,
            description = args.description,
            createdAt = now,
            updatedAt = now,
            userId = userId,
        )
        projectsRepository.create(project)
        return Json.encodeToString(
            CreateProjectOutput.serializer(),
            CreateProjectOutput(projectId.value, project.name),
        )
    }

    companion object {
        const val NAME = "create_project"
        const val DESCRIPTION = "Creates a new project."
    }
}
