package com.singularity.todo.feature.ai.use_cases

import com.singularity.todo.feature.ai.tools.PickTimeInput
import com.singularity.todo.feature.ai.tools.PickTimeOutput
import com.singularity.todo.feature.ai.tools.PickTimeTool
import kotlinx.serialization.json.Json

/**
 * Use case: suggest the best time slot for today or tomorrow for a task.
 */
class PickTimeUseCase(private val tool: PickTimeTool) {

    suspend operator fun invoke(
        title: String,
        description: String? = null
    ): Result<String> = runCatching {
        val json = tool.execute(PickTimeInput(title, description))
        Json.decodeFromString<PickTimeOutput>(json).suggestedTime
    }
}
