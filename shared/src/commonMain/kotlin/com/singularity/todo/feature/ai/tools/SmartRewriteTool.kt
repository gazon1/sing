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
data class SmartRewriteInput(val rawIdea: String)

@Serializable
data class SmartRewriteOutput(val newTitle: String)

class SmartRewriteTool(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel
) : SimpleTool<SmartRewriteInput>(TypeToken.of(SmartRewriteInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: SmartRewriteInput): String {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(Prompts.smartRewriteSystem)
            user(Prompts.smartRewriteUser(args.rawIdea))
        }
        val response = promptExecutor.execute(p, model, emptyList())
        val text = extractText(response)
        return Json.encodeToString(SmartRewriteOutput.serializer(), SmartRewriteOutput(text.trim()))
    }

    companion object {
        const val NAME = "smart_rewrite"
        const val DESCRIPTION = "Craft a concise, actionable task title from a raw idea."

        private fun extractText(response: Message.Assistant): String =
            response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()
    }
}
