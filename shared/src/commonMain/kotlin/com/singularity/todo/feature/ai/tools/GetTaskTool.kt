package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable
data class GetTaskInput(val taskId: String)

@Serializable
data class GetTaskOutput(
    val id: String,
    val title: String,
    val description: String?,
    val isCompleted: Boolean,
    val projectId: String?,
)

class GetTaskTool(private val taskRepository: TaskRepository) :
    SimpleTool<GetTaskInput>(TypeToken.of(GetTaskInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: GetTaskInput): String {
        val task = taskRepository.observeForCurrentUser(TaskId(args.taskId)).first()
        val output = if (task != null) {
            GetTaskOutput(task.id.value, task.title, task.description, task.isCompleted, task.projectId?.value)
        } else {
            GetTaskOutput(args.taskId, "(not found)", null, false, null)
        }
        return kotlinx.serialization.json.Json.encodeToString(GetTaskOutput.serializer(), output)
    }

    companion object {
        const val NAME = "get_task"
        const val DESCRIPTION = "Fetch a single task by its ID."
    }
}
