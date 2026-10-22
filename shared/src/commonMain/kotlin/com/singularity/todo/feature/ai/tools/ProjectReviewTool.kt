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
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.ai.prompts.Prompts
import com.singularity.todo.feature.tasks.domain.logic.TaskSelection
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
data class ProjectReviewInput(
    val projectName: String,
    /** The tasks of the project, when the reader names them. */
    val tasks: List<String> = emptyList(),
    /** The app's own set of tasks to review, when the reader names one instead. */
    val selection: TaskSelection? = null,
)

class ProjectReviewTool(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val taskRepository: TaskRepository,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
) : SimpleTool<ProjectReviewInput>(TypeToken.of(ProjectReviewInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: ProjectReviewInput): String {
        val tasks = args.selection
            ?.let { selection ->
                TaskToolSupport.select(selection, taskRepository, clock, timeZoneProvider.current())
            }
            ?: args.tasks
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(Prompts.projectReviewSystem)
            user(Prompts.projectReviewUser(args.projectName, tasks))
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
