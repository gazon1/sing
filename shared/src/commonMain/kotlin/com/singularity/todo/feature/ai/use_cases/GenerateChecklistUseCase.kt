package com.singularity.todo.feature.ai.use_cases

import com.singularity.todo.feature.ai.tools.GenerateChecklistInput
import com.singularity.todo.feature.ai.tools.GenerateChecklistOutput
import com.singularity.todo.feature.ai.tools.GenerateChecklistTool
import kotlinx.serialization.json.Json

/**
 * Use case: generate a checklist of 3-8 concrete steps to complete a task.
 */
class GenerateChecklistUseCase(private val tool: GenerateChecklistTool) {

    suspend operator fun invoke(
        title: String,
        description: String? = null
    ): Result<List<String>> = runCatching {
        val json = tool.execute(GenerateChecklistInput(title, description))
        Json.decodeFromString<GenerateChecklistOutput>(json).steps
    }
}
