package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.TypeToken
import ai.koog.utils.time.KoogClock
import com.singularity.todo.feature.ai.prompts.Prompts
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GenerateDescriptionInput(val title: String)

@Serializable
data class GenerateDescriptionOutput(val description: String)

class GenerateDescriptionTool(private val promptExecutor: PromptExecutor, private val model: LLModel) :
    SimpleTool<GenerateDescriptionInput>(TypeToken.of(GenerateDescriptionInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: GenerateDescriptionInput): String {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(Prompts.generateDescriptionSystem)
            user(Prompts.generateDescriptionUser(args.title))
        }
        val response = promptExecutor.execute(p, model, emptyList())
        val text = extractText(response)
        return Json.encodeToString(GenerateDescriptionOutput.serializer(), GenerateDescriptionOutput(text.trim()))
    }

    companion object {
        const val NAME = "generate_description"
        const val DESCRIPTION = "Generate a brief description for a task title."

        private fun extractText(response: Message.Assistant): String =
            response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()
    }
}
