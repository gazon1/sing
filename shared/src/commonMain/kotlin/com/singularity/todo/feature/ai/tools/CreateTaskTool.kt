package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CreateTaskInput(
    val title: String,
    val description: String? = null,
    val priority: String = "None",
    val kind: String = "Task",
    val projectId: String? = null,
    val parentTaskId: String? = null,
    val tagIds: List<String> = emptyList(),
    val dueDate: String? = null, // "YYYY-MM-DD"
    val dueTime: String? = null, // "HH:mm"
    val someday: Boolean = false,
)

@Serializable
data class CreateTaskOutput(val taskId: String, val title: String, val description: String?)

class CreateTaskTool(
    private val taskRepository: TaskRepository,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) : SimpleTool<CreateTaskInput>(TypeToken.of(CreateTaskInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: CreateTaskInput): String {
        val now = clock.now()
        val taskId = TaskId.generate()
        val userId = currentUser.scopedUserId.value
        val task = Task(
            id = taskId,
            title = args.title,
            description = args.description,
            priority = runCatching { TaskPriority.valueOf(args.priority) }.getOrDefault(TaskPriority.None),
            kind = runCatching { TaskKind.valueOf(args.kind) }.getOrDefault(TaskKind.Task),
            projectId = args.projectId?.let { ProjectId.fromString(it) },
            parentTaskId = args.parentTaskId?.let { TaskId.fromString(it) },
            tags = args.tagIds.map { TagId.fromString(it) },
            dueDate = args.dueDate?.let { LocalDate.parse(it) },
            dueTime = args.dueTime?.let { com.singularity.todo.core.database.LocalTimeFormats.parse(it) },
            someday = args.someday,
            createdAt = now,
            updatedAt = now,
            userId = userId,
        )
        taskRepository.create(task)
        return Json.encodeToString(
            CreateTaskOutput.serializer(),
            CreateTaskOutput(taskId.value, task.title, task.description),
        )
    }

    companion object {
        const val NAME = "create_task"
        const val DESCRIPTION = "Creates a new task. All fields optional except title."
    }
}
