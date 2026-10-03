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
import kotlinx.coroutines.CancellationException

@Serializable
data class SummarizeNoteInput(val title: String, val body: String)

@Serializable
data class SummarizeNoteOutput(val summary: String)

class SummarizeNoteTool(private val promptExecutor: PromptExecutor, private val model: LLModel) :
    SimpleTool<SummarizeNoteInput>(TypeToken.of(SummarizeNoteInput::class.java), NAME, DESCRIPTION) {

    private val logger = Logger.withTag("SummarizeNote")

    override suspend fun execute(args: SummarizeNoteInput): String {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(Prompts.summarizeNoteSystem)
            user(Prompts.summarizeNoteUser(args.title, args.body))
        }
        val response = promptExecutor.execute(p, model, emptyList())
        val text = extractText(response)
        return try {
            kotlinx.serialization.json.Json.decodeFromString<SummarizeNoteOutput>(text).let { out ->
                kotlinx.serialization.json.Json.encodeToString(SummarizeNoteOutput.serializer(), out)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "failed" }
            kotlinx.serialization.json.Json.encodeToString(
                SummarizeNoteOutput.serializer(),
                SummarizeNoteOutput("Summary unavailable."),
            )
        }
    }

    companion object {
        const val NAME = "summarize_note"
        const val DESCRIPTION = "Summarize a note in one sentence."

        private fun extractText(response: Message.Assistant): String =
            response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()
    }
}
