package com.singularity.todo.feature.ai.use_cases

import com.singularity.todo.feature.ai.tools.SmartRewriteInput
import com.singularity.todo.feature.ai.tools.SmartRewriteOutput
import com.singularity.todo.feature.ai.tools.SmartRewriteTool
import kotlinx.serialization.json.Json

/**
 * Use case: craft a concise, actionable task title from a raw idea.
 */
class SmartRewriteUseCase(private val tool: SmartRewriteTool) {

    suspend operator fun invoke(rawIdea: String): Result<String> = runCatching {
        val json = tool.execute(SmartRewriteInput(rawIdea))
        Json.decodeFromString<SmartRewriteOutput>(json).newTitle
    }
}
