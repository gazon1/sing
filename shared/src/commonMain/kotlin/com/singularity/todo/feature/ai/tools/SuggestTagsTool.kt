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
import co.touchlab.kermit.Logger
import com.singularity.todo.feature.ai.prompts.Prompts
import kotlinx.serialization.Serializable

@Serializable
data class SuggestTagsInput(val title: String, val body: String)

@Serializable
data class SuggestTagsOutput(val tags: List<String>)

class SuggestTagsTool(private val promptExecutor: PromptExecutor, private val model: LLModel) :
    SimpleTool<SuggestTagsInput>(TypeToken.of(SuggestTagsInput::class.java), NAME, DESCRIPTION) {

    private val logger = Logger.withTag("SuggestTags")

    override suspend fun execute(args: SuggestTagsInput): String {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(Prompts.suggestTagsSystem)
            user(Prompts.suggestTagsUser(args.title, args.body))
        }
        val response = promptExecutor.execute(p, model, emptyList())
        val text = extractText(response)
        return try {
            kotlinx.serialization.json.Json.decodeFromString<SuggestTagsOutput>(text).let { out ->
                kotlinx.serialization.json.Json.encodeToString(SuggestTagsOutput.serializer(), out)
            }
        } catch (e: Exception) {
            logger.w(e) { "failed" }
            kotlinx.serialization.json.Json.encodeToString(
                SuggestTagsOutput.serializer(),
                SuggestTagsOutput(emptyList()),
            )
        }
    }

    companion object {
        const val NAME = "suggest_tags"
        const val DESCRIPTION = "Suggest tags for a note."

        private fun extractText(response: Message.Assistant): String =
            response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()
    }
}
