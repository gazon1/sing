package com.singularity.todo.feature.ai.use_cases

import com.singularity.todo.feature.ai.tools.RefineTaskInput
import com.singularity.todo.feature.ai.tools.RefineTaskOutput
import com.singularity.todo.feature.ai.tools.RefineTaskTool
import kotlinx.serialization.json.Json

/**
 * Use case: refine a task title to be clearer and more actionable.
 * Calls the AI once, returns the rewritten title.
 */
class RefineTaskUseCase(private val tool: RefineTaskTool) {

    suspend operator fun invoke(
        currentTitle: String,
        description: String? = null
    ): Result<String> = runCatching {
        val json = tool.execute(RefineTaskInput(currentTitle, description))
        Json.decodeFromString<RefineTaskOutput>(json).newTitle
    }
}
