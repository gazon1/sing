package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import com.singularity.todo.feature.ai.prompts.Prompts
import kotlinx.serialization.Serializable
import ai.koog.prompt.Prompt
import ai.koog.utils.time.KoogClock
import kotlinx.serialization.json.Json

@Serializable
data class RefineTaskInput(val currentTitle: String, val description: String? = null)

@Serializable
data class RefineTaskOutput(val newTitle: String)

class RefineTaskTool(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel
) : SimpleTool<RefineTaskInput>(TypeToken.of(RefineTaskInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: RefineTaskInput): String {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(Prompts.refineSystem)
            user(Prompts.refineUser(args.currentTitle, args.description))
        }
        val response = promptExecutor.execute(p, model, emptyList())
        val text = extractText(response)
        return try {
            Json.encodeToString(RefineTaskOutput.serializer(), RefineTaskOutput(text.trim()))
        } catch (_: Exception) {
            Json.encodeToString(RefineTaskOutput.serializer(), RefineTaskOutput(text.trim()))
        }
    }

    companion object {
        const val NAME = "refine_task"
        const val DESCRIPTION = "Rewrite the task title to be clearer and more actionable."

        private fun extractText(response: Message.Assistant): String =
            response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()
    }
}
