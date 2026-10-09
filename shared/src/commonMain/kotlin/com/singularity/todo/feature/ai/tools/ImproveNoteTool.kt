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
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CancellationException

@Serializable
data class ImproveNoteInput(val title: String, val body: String)

@Serializable
data class ImproveNoteOutput(val title: String, val body: String)

class ImproveNoteTool(private val promptExecutor: PromptExecutor, private val model: LLModel) :
    SimpleTool<ImproveNoteInput>(TypeToken.of(ImproveNoteInput::class.java), NAME, DESCRIPTION),
    TypedTool<ImproveNoteInput, ImproveNoteOutput> {

    private val logger = Logger.withTag("ImproveNote")

    /**
     * Agent path: returns JSON string (used by Koog executor + encodeResultToString).
     * The double-encode is intentional — the agent needs a JSON string, not a deserialized object.
     */
    override suspend fun execute(args: ImproveNoteInput): String {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(
                "You are an expert writing assistant. Improve the following note for clarity, conciseness, and readability. Return a JSON object with 'title' (improved title, max 80 chars) and 'body' (improved content, markdown supported). Preserve the original intent.",
            )
            user("Title: ${args.title}\n\nBody:\n${args.body}")
        }
        val response = promptExecutor.execute(p, model, emptyList())
        val text = extractText(response)
        return try {
            kotlinx.serialization.json.Json.decodeFromString<ImproveNoteOutput>(text).let { out ->
                kotlinx.serialization.json.Json.encodeToString(ImproveNoteOutput.serializer(), out)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "failed" }
            // Fallback: return original if parsing fails
            kotlinx.serialization.json.Json.encodeToString(
                ImproveNoteOutput.serializer(),
                ImproveNoteOutput(args.title, args.body),
            )
        }
    }

    /**
     * Use-case path: returns the deserialized domain object directly.
     * Eliminates the JSON → String → JSON round-trip of the agent path.
     */
    override suspend fun executeTyped(args: ImproveNoteInput): ImproveNoteOutput {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(
                "You are an expert writing assistant. Improve the following note for clarity, conciseness, and readability. Return a JSON object with 'title' (improved title, max 80 chars) and 'body' (improved content, markdown supported). Preserve the original intent.",
            )
            user("Title: ${args.title}\n\nBody:\n${args.body}")
        }
        val response = promptExecutor.execute(p, model, emptyList())
        val text = extractText(response)
        return try {
            kotlinx.serialization.json.Json.decodeFromString<ImproveNoteOutput>(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "failed" }
            ImproveNoteOutput(args.title, args.body)
        }
    }

    companion object {
        const val NAME = "improve_note"
        const val DESCRIPTION = "Improve note clarity, conciseness, and readability."

        private fun extractText(response: Message.Assistant): String =
            response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()
    }
}
