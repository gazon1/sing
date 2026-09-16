package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

@Serializable
data class CreateProjectOutput(val projectId: String, val name: String)

class CreateProjectTool(
    private val projectsRepository: ProjectsRepository,
    private val profileAwareCurrentUser: ProfileAwareCurrentUser,
    private val clock: Clock,
) : SimpleTool<CreateProjectInput>(TypeToken.of(CreateProjectInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: CreateProjectInput): String {
        val now = clock.now()
        val projectId = ProjectId.generate()
        val userId = profileAwareCurrentUser.scopedUserId.value.value
        val finalColor = parseColor(args.colorHex, defaultColor = args.color)
        val project = Project(
            id = projectId,
            name = args.name,
            color = finalColor,
            icon = args.icon,
            description = args.description,
            createdAt = now,
            updatedAt = now,
            userId = UserId(userId),
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
