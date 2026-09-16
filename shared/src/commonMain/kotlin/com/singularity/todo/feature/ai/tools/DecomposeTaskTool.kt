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
data class DecomposeTaskInput(val title: String, val description: String? = null)

@Serializable
data class DecomposeTaskOutput(val subTasks: List<String>)

class DecomposeTaskTool(private val promptExecutor: PromptExecutor, private val model: LLModel) :
    SimpleTool<DecomposeTaskInput>(TypeToken.of(DecomposeTaskInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: DecomposeTaskInput): String {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(Prompts.decomposeTaskSystem)
            user(Prompts.decomposeTaskUser(args.title, args.description))
        }
        val response = promptExecutor.execute(p, model, emptyList())
        val text = extractText(response)
        return try {
            val items = Json.decodeFromString<DecomposeTaskOutput>(text).subTasks
            Json.encodeToString(DecomposeTaskOutput.serializer(), DecomposeTaskOutput(items))
        } catch (_: Exception) {
            val lines = text.lines()
                .filter { it.isNotBlank() && !it.startsWith("[") && !it.startsWith("]") }
                .map { it.trim().removePrefix("- ").removePrefix("* ").removeSurrounding("\"") }
            Json.encodeToString(DecomposeTaskOutput.serializer(), DecomposeTaskOutput(lines))
        }
    }

    companion object {
        const val NAME = "decompose_task"
        const val DESCRIPTION = "Break a task into smaller, actionable sub-tasks."

        private fun extractText(response: Message.Assistant): String =
            response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()
    }
}
