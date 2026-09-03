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
data class GenerateChecklistInput(val title: String, val description: String? = null)

@Serializable
data class GenerateChecklistOutput(val steps: List<String>)

class GenerateChecklistTool(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel
) : SimpleTool<GenerateChecklistInput>(TypeToken.of(GenerateChecklistInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: GenerateChecklistInput): String {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(Prompts.generateChecklistSystem)
            user(Prompts.generateChecklistUser(args.title, args.description))
        }
        val response = promptExecutor.execute(p, model, emptyList())
        val text = extractText(response)
        return try {
            val steps = Json.decodeFromString<GenerateChecklistOutput>(text).steps
            Json.encodeToString(GenerateChecklistOutput.serializer(), GenerateChecklistOutput(steps))
        } catch (_: Exception) {
            val lines = text.lines()
                .filter { it.isNotBlank() && !it.startsWith("[") && !it.startsWith("]") }
                .map { it.trim().removePrefix("- ").removePrefix("* ").removeSurrounding("\"") }
            Json.encodeToString(GenerateChecklistOutput.serializer(), GenerateChecklistOutput(lines))
        }
    }

    companion object {
        const val NAME = "generate_checklist"
        const val DESCRIPTION = "Generate a checklist of steps to complete a task."

        private fun extractText(response: Message.Assistant): String =
            response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()
    }
}
