package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * MR-1: Sets the dependency edge for a task.
 * Replaces the entire `dependsOn` set (idempotent — setting the same set is a no-op).
 *
 * Blocking semantics: if any dependency task is incomplete and not trashed, the
 * dependent task shows a [com.singularity.todo.feature.tasks.domain.logic.TaskComputed.isBlocked]
 * badge in the UI. Completion of the dependent is not blocked at the data layer —
 * that enforcement is the UI's responsibility.
 *
 * @param taskId           The task to set dependencies on
 * @param dependsOnTaskIds The new set of task IDs this task depends on.
 *                         Pass an empty list to clear all dependencies.
 */
@Serializable
data class SetDependenciesInput(
    val taskId: String,
    val dependsOnTaskIds: List<String>,
)

@Serializable
data class SetDependenciesOutput(
    val taskId: String,
    val dependsOnTaskIds: List<String>,
    val success: Boolean,
    val error: String? = null,
)

/**
 * MCP tool: `task.set_dependencies`
 *
 * ## Idempotency
 * Calling with the same [dependsOnTaskIds] twice yields the same result — the join table
 * upsert is a no-op on duplicate primary keys.
 *
 * ## Validation
 * - [taskId] must exist in the repository
 * - Each ID in [dependsOnTaskIds] must exist in the repository
 * - A task may not depend on itself
 */
class SetDependenciesTool(private val taskRepo: TaskRepository) :
    SimpleTool<SetDependenciesInput>(TypeToken.of(SetDependenciesInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: SetDependenciesInput): String {
        val taskId = try {
            TaskId.fromString(args.taskId)
        } catch (_: Exception) {
            return Json.encodeToString(
                SetDependenciesOutput.serializer(),
                SetDependenciesOutput(args.taskId, args.dependsOnTaskIds, false, "Invalid task ID format"),
            )
        }

        if (!taskRepo.exists(taskId)) {
            return Json.encodeToString(
                SetDependenciesOutput.serializer(),
                SetDependenciesOutput(args.taskId, args.dependsOnTaskIds, false, "Task not found"),
            )
        }

        if (args.taskId in args.dependsOnTaskIds) {
            return Json.encodeToString(
                SetDependenciesOutput.serializer(),
                SetDependenciesOutput(args.taskId, args.dependsOnTaskIds, false, "A task cannot depend on itself"),
            )
        }

        val depIds = args.dependsOnTaskIds.mapNotNull { id ->
            try {
                TaskId.fromString(id)
            } catch (_: Exception) {
                null
            }
        }

        if (depIds.size != args.dependsOnTaskIds.size) {
            return Json.encodeToString(
                SetDependenciesOutput.serializer(),
                SetDependenciesOutput(args.taskId, args.dependsOnTaskIds, false, "One or more dependency IDs have invalid format"),
            )
        }

        val missing = depIds.filter { !taskRepo.exists(it) }
        if (missing.isNotEmpty()) {
            return Json.encodeToString(
                SetDependenciesOutput.serializer(),
                SetDependenciesOutput(args.taskId, args.dependsOnTaskIds, false, "Dependency task(s) not found: ${missing.joinToString { it.value }}"),
            )
        }

        val result = taskRepo.setDependencies(taskId, depIds.toSet())
        return if (result.isSuccess) {
            Json.encodeToString(
                SetDependenciesOutput.serializer(),
                SetDependenciesOutput(args.taskId, args.dependsOnTaskIds, true),
            )
        } else {
            Json.encodeToString(
                SetDependenciesOutput.serializer(),
                SetDependenciesOutput(args.taskId, args.dependsOnTaskIds, false, result.exceptionOrNull()?.message ?: "Unknown error"),
            )
        }
    }

    companion object {
        const val NAME = "task.set_dependencies"
        const val DESCRIPTION = "Sets the tasks that a given task depends on. Pass an empty list to clear all dependencies. Idempotent — repeated calls with the same input produce the same result."
    }
}
