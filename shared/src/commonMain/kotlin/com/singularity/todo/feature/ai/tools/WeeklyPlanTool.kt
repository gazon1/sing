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
data class WeeklyPlanInput(val tasks: List<String>)

@Serializable
data class WeeklyPlanOutput(val items: List<String>)

class WeeklyPlanTool(private val promptExecutor: PromptExecutor, private val model: LLModel) :
    SimpleTool<WeeklyPlanInput>(TypeToken.of(WeeklyPlanInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: WeeklyPlanInput): String {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(Prompts.weeklyPlanSystem)
            user(Prompts.weeklyPlanUser(args.tasks))
        }
        val response = promptExecutor.execute(p, model, emptyList())
        val text = extractText(response)
        return try {
            val items = Json.decodeFromString<WeeklyPlanOutput>(text).items
            Json.encodeToString(WeeklyPlanOutput.serializer(), WeeklyPlanOutput(items))
        } catch (_: Exception) {
            val lines = text.lines()
                .filter { it.isNotBlank() && !it.startsWith("[") && !it.startsWith("]") }
                .map { it.trim().removePrefix("- ").removePrefix("* ").removeSurrounding("\"") }
            Json.encodeToString(WeeklyPlanOutput.serializer(), WeeklyPlanOutput(lines))
        }
    }

    companion object {
        const val NAME = "weekly_plan"
        const val DESCRIPTION = "Suggest 3-5 tasks to focus on this week."

        private fun extractText(response: Message.Assistant): String =
            response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()
    }
}
