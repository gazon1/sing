package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable
data class ListTasksInput(val userId: String = "", val projectId: String? = null, val limit: Int = 50)

@Serializable
data class ListTasksOutput(val tasks: List<TaskSummary>)

@Serializable
data class TaskSummary(val id: String, val title: String, val isCompleted: Boolean, val projectId: String?)

class ListTasksTool(private val taskRepository: TaskRepository) :
    SimpleTool<ListTasksInput>(TypeToken.of(ListTasksInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: ListTasksInput): String {
        // MCP callers can pass an explicit userId to run as a specific user.
        // When blank, use the current profile's scoped userId.
        val effectiveUserId = if (args.userId.isNotBlank()) UserId(args.userId) else ProfileAwareCurrentUser.current
        val filter = args.projectId?.let {
            TaskFilter.ByProject(
                com.singularity.todo.feature.projects.domain.model.ProjectId(it),
            )
        } ?: TaskFilter.All
        val tasks = taskRepository.watchTasks(effectiveUserId, filter).first().take(args.limit)
            .map { TaskSummary(it.id.value, it.title, it.isCompleted, it.projectId?.value) }
        return kotlinx.serialization.json.Json.encodeToString(ListTasksOutput.serializer(), ListTasksOutput(tasks))
    }

    companion object {
        const val NAME = "list_tasks"
        const val DESCRIPTION = "List tasks, optionally filtered by project."
    }
}
