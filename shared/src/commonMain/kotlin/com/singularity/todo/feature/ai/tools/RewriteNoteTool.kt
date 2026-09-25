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

enum class RewriteTone {
    OneLiner,
    Tldr,
    Structured,
}

@Serializable
data class RewriteNoteInput(val title: String, val body: String, val tone: String = "OneLiner")

@Serializable
data class RewriteNoteOutput(val title: String, val body: String)

class RewriteNoteTool(private val promptExecutor: PromptExecutor, private val model: LLModel) :
    SimpleTool<RewriteNoteInput>(TypeToken.of(RewriteNoteInput::class.java), NAME, DESCRIPTION) {

    private val logger = Logger.withTag("RewriteNote")

    override suspend fun execute(args: RewriteNoteInput): String {
        val toneLabel = args.tone
        val systemPrompt = when (args.tone) {
            "Tldr" -> """
                You are an expert writing assistant. Rewrite the following note as a concise TLDR —
                a short, punchy summary with the most important takeaways. Return a JSON object
                with 'title' and 'body' fields.
            """.trimIndent()

            "Structured" -> """
                You are an expert writing assistant. Rewrite the following note with clear structure —
                headings, bullet points, and sections. Return a JSON object with 'title' and 'body' fields.
            """.trimIndent()

            else -> Prompts.rewriteNoteSystem + " Style: one concise paragraph."
        }
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(systemPrompt)
            user(Prompts.rewriteNoteUser(args.title, args.body, toneLabel))
        }
        val response = promptExecutor.execute(p, model, emptyList())
        val text = extractText(response)
        return try {
            kotlinx.serialization.json.Json.decodeFromString<RewriteNoteOutput>(text).let { out ->
                kotlinx.serialization.json.Json.encodeToString(RewriteNoteOutput.serializer(), out)
            }
        } catch (e: Exception) {
            logger.w(e) { "failed" }
            kotlinx.serialization.json.Json.encodeToString(
                RewriteNoteOutput.serializer(),
                RewriteNoteOutput(args.title, args.body),
            )
        }
    }

    companion object {
        const val NAME = "rewrite_note"
        const val DESCRIPTION = "Rewrite a note in a different style (OneLiner, TLDR, or Structured)."

        private fun extractText(response: Message.Assistant): String =
            response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()
    }
}
