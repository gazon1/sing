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
import kotlinx.coroutines.CancellationException

@Serializable
data class ClusterTasksInput(val tasks: List<String>)

@Serializable
data class ClusterTasksOutput(val clusters: Map<String, List<String>>)

class ClusterTasksTool(private val promptExecutor: PromptExecutor, private val model: LLModel) :
    SimpleTool<ClusterTasksInput>(TypeToken.of(ClusterTasksInput::class.java), NAME, DESCRIPTION) {

    private val logger = Logger.withTag("ClusterTasks")

    override suspend fun execute(args: ClusterTasksInput): String {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(Prompts.clusterTasksSystem)
            user(Prompts.clusterTasksUser(args.tasks))
        }
        val response = promptExecutor.execute(p, model, emptyList())
        val text = extractText(response)
        return try {
            Json.decodeFromString<ClusterTasksOutput>(text).let { out ->
                Json.encodeToString(ClusterTasksOutput.serializer(), out)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "failed" }
            "{}"
        }
    }

    companion object {
        const val NAME = "cluster_tasks"
        const val DESCRIPTION = "Group task titles into thematic clusters."

        private fun extractText(response: Message.Assistant): String =
            response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()
    }
}
