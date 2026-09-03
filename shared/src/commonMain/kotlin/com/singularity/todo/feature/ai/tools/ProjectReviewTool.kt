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

@Serializable
data class ProjectReviewInput(val projectName: String, val tasks: List<String>)

class ProjectReviewTool(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel
) : SimpleTool<ProjectReviewInput>(TypeToken.of(ProjectReviewInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: ProjectReviewInput): String {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(Prompts.projectReviewSystem)
            user(Prompts.projectReviewUser(args.projectName, args.tasks))
        }
        val response = promptExecutor.execute(p, model, emptyList())
        return extractText(response)
    }

    companion object {
        const val NAME = "project_review"
        const val DESCRIPTION = "Review a project, identify risks, and suggest the next action."

        private fun extractText(response: Message.Assistant): String =
            response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()
    }
}
