package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

@Serializable
data class UpdateTaskInput(
    val taskId: String,
    val title: String? = null,
    val description: String? = null,
    val priority: String? = null,
    val kind: String? = null,
    val projectId: String? = null,
    val tagIds: List<String>? = null,
    val dueDate: String? = null, // "YYYY-MM-DD" or null to clear
    val dueTime: String? = null, // "HH:mm" or null to clear
    val someday: Boolean? = null,
)

@Serializable
data class UpdateTaskOutput(val taskId: String, val updated: Boolean)

class UpdateTaskTool(private val taskRepository: TaskRepository, private val clock: Clock) :
    SimpleTool<UpdateTaskInput>(TypeToken.of(UpdateTaskInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: UpdateTaskInput): String {
        val existing = taskRepository.observe(TaskId(args.taskId)).first()
            ?: return Json.encodeToString(
                UpdateTaskOutput.serializer(),
                UpdateTaskOutput(args.taskId, false),
            )

        val updated = existing.copy(
            title = args.title ?: existing.title,
            description = args.description ?: existing.description,
            priority = args.priority?.let {
                runCatching {
                    TaskPriority.valueOf(
                        it,
                    )
                }.getOrDefault(existing.priority)
            } ?: existing.priority,
            kind = args.kind?.let { runCatching { TaskKind.valueOf(it) }.getOrDefault(existing.kind) } ?: existing.kind,
            projectId = args.projectId?.let { ProjectId.fromString(it) } ?: existing.projectId,
            tags = args.tagIds?.map { TagId.fromString(it) } ?: existing.tags,
            dueDate = args.dueDate?.let { if (it.isBlank()) null else LocalDate.parse(it) } ?: existing.dueDate,
            dueTime =
                args.dueTime?.let {
                    if (it.isBlank()) {
                        null
                    } else {
                        com.singularity.todo.core.database.LocalTimeFormats.parse(
                            it,
                        )
                    }
                } ?: existing.dueTime,
            someday = args.someday ?: existing.someday,
            updatedAt = clock.now(),
        )
        taskRepository.update(updated)
        return Json.encodeToString(
            UpdateTaskOutput.serializer(),
            UpdateTaskOutput(args.taskId, true),
        )
    }

    companion object {
        const val NAME = "update_task"
        const val DESCRIPTION = "Updates an existing task. Pass only fields to change."
    }
}
