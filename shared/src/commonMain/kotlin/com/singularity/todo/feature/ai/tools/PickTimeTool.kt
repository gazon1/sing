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
data class PickTimeInput(val title: String, val description: String? = null)

@Serializable
data class PickTimeOutput(val suggestedTime: String)

class PickTimeTool(private val promptExecutor: PromptExecutor, private val model: LLModel) :
    SimpleTool<PickTimeInput>(TypeToken.of(PickTimeInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: PickTimeInput): String {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(Prompts.pickTimeSystem)
            user(Prompts.pickTimeUser(args.title, args.description))
        }
        val response = promptExecutor.execute(p, model, emptyList())
        val text = extractText(response)
        return Json.encodeToString(PickTimeOutput.serializer(), PickTimeOutput(text.trim()))
    }

    companion object {
        const val NAME = "pick_time"
        const val DESCRIPTION = "Suggest the best time slot for today or tomorrow for a task."

        private fun extractText(response: Message.Assistant): String =
            response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()
    }
}
