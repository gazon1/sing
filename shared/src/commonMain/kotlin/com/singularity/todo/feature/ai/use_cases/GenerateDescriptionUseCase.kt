package com.singularity.todo.feature.ai.use_cases

import com.singularity.todo.feature.ai.tools.GenerateDescriptionInput
import com.singularity.todo.feature.ai.tools.GenerateDescriptionOutput
import com.singularity.todo.feature.ai.tools.GenerateDescriptionTool
import kotlinx.serialization.json.Json

/**
 * Use case: generate a brief, clear description for a task title.
 */
class GenerateDescriptionUseCase(private val tool: GenerateDescriptionTool) {

    suspend operator fun invoke(title: String): Result<String> = runCatching {
        val json = tool.execute(GenerateDescriptionInput(title))
        Json.decodeFromString<GenerateDescriptionOutput>(json).description
    }
}
