package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable
data class ListLinkedTasksInput(val projectId: String)

@Serializable
data class ListLinkedTasksOutput(val tasks: List<TaskSummary>)

class ListLinkedTasksTool(
    private val taskRepository: TaskRepository,
) : SimpleTool<ListLinkedTasksInput>(TypeToken.of(ListLinkedTasksInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: ListLinkedTasksInput): String {
        val tasks = taskRepository.observeByFilter(TaskFilter.ByProject(ProjectId(args.projectId))).first()
            .map { TaskSummary(it.id.value, it.title, it.isCompleted, it.projectId?.value) }
        return kotlinx.serialization.json.Json.encodeToString(
            ListLinkedTasksOutput.serializer(),
            ListLinkedTasksOutput(tasks),
        )
    }

    companion object {
        const val NAME = "list_linked_tasks"
        const val DESCRIPTION = "List all tasks linked to a specific project."
    }
}
