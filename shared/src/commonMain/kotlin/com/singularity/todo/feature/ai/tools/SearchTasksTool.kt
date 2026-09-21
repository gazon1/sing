package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable
data class SearchTasksInput(val query: String, val limit: Int = 20)

@Serializable
data class SearchTasksOutput(val tasks: List<TaskSummary>)

class SearchTasksTool(private val taskRepository: TaskRepository) :
    SimpleTool<SearchTasksInput>(TypeToken.of(SearchTasksInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: SearchTasksInput): String {
        val tasks = taskRepository.observeByFilter(TaskFilter.All).first()
            .filter { it.title.contains(args.query, ignoreCase = true) }
            .take(args.limit)
            .map { TaskSummary(it.id.value, it.title, it.isCompleted, it.projectId?.value) }
        return kotlinx.serialization.json.Json.encodeToString(SearchTasksOutput.serializer(), SearchTasksOutput(tasks))
    }

    companion object {
        const val NAME = "search_tasks"
        const val DESCRIPTION = "Search tasks by title (case-insensitive)."
    }
}
