package com.singularity.todo.feature.ai.use_cases

import com.singularity.todo.feature.ai.tools.DecomposeTaskInput
import com.singularity.todo.feature.ai.tools.DecomposeTaskOutput
import com.singularity.todo.feature.ai.tools.DecomposeTaskTool
import kotlinx.serialization.json.Json

/**
 * Use case: break a task into 3-7 smaller, actionable sub-tasks.
 */
class DecomposeTaskUseCase(private val tool: DecomposeTaskTool) {

    suspend operator fun invoke(
        title: String,
        description: String? = null
    ): Result<List<String>> = runCatching {
        val json = tool.execute(DecomposeTaskInput(title, description))
        Json.decodeFromString<DecomposeTaskOutput>(json).subTasks
    }
}
