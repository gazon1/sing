package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.feature.tasks.TaskId
import com.singularity.todo.feature.tasks.TaskRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DeleteTaskInput(val taskId: String)

@Serializable
data class DeleteTaskOutput(
    val taskId: String,
    val deleted: Boolean,
    val error: String? = null,
)

class DeleteTaskTool(private val taskRepository: TaskRepository) :
    SimpleTool<DeleteTaskInput>(TypeToken.of(DeleteTaskInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: DeleteTaskInput): String {
        val result = taskRepository.softDelete(TaskId(args.taskId))
        return Json.encodeToString(
            DeleteTaskOutput.serializer(),
            DeleteTaskOutput(
                taskId = args.taskId,
                deleted = result.isSuccess,
                error = result.exceptionOrNull()?.message,
            ),
        )
    }

    companion object {
        const val NAME = "delete_task"
        const val DESCRIPTION = "Soft-deletes (archives) a task by its ID."
    }
}
