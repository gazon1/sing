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
import kotlinx.serialization.json.Json

@Serializable
data class ClusterNotesInput(val notes: List<String>)

@Serializable
data class ClusterNotesOutput(val clusters: Map<String, List<String>>)

class ClusterNotesTool(private val promptExecutor: PromptExecutor, private val model: LLModel) :
    SimpleTool<ClusterNotesInput>(TypeToken.of(ClusterNotesInput::class.java), NAME, DESCRIPTION) {

    private val logger = Logger.withTag("ClusterNotes")

    override suspend fun execute(args: ClusterNotesInput): String {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(Prompts.clusterNotesSystem)
            user(Prompts.clusterNotesUser(args.notes))
        }
        val response = promptExecutor.execute(p, model, emptyList())
        val text = extractText(response)
        return try {
            Json.decodeFromString<ClusterNotesOutput>(text).let { out ->
                Json.encodeToString(ClusterNotesOutput.serializer(), out)
            }
        } catch (e: Exception) {
            logger.w(e) { "failed" }
            "{}"
        }
    }

    companion object {
        const val NAME = "cluster_notes"
        const val DESCRIPTION = "Group note titles into thematic clusters."

        private fun extractText(response: Message.Assistant): String =
            response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()
    }
}
