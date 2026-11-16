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
import com.singularity.todo.core.tasks.TasksSettingsRepository
import com.singularity.todo.feature.ai.prompts.Prompts
import com.singularity.todo.feature.notes.domain.port.NotesRepository
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.tasks.domain.logic.TaskSelection
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
data class ProjectReviewInput(
    /** The name the caller knows the project by; unused when [projectId] is given. */
    val projectName: String = "",
    /** The tasks of the project, when the reader names them. */
    val tasks: List<String> = emptyList(),
    /** The app's own set of tasks to review, when the reader names one instead. */
    val selection: TaskSelection? = null,
    /**
     * The project to review, when the caller knows it.
     *
     * With it, the review is grounded in the project's own rows — its tasks,
     * their health, the notes attached to them — instead of in a name and a list
     * of titles the model made up.
     */
    val projectId: String? = null,
)

/**
 * Reviews a project: what is stuck, what is drifting, and what to do next.
 *
 * ## The two ways in
 *
 * A caller that names the project and its tasks gets what it always got — a review
 * of those titles. A caller that knows the project's id gets a [ProjectBrief]: the
 * project's own tasks, classified by the app's own health rules, and the notes
 * attached to them. The tool name and the output are the same either way.
 *
 * A project id that names no project is answered with that fact rather than with
 * a review of a name.
 */
class ProjectReviewTool(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
    private val taskRepository: TaskRepository,
    private val projectsRepository: ProjectsRepository,
    private val notesRepository: NotesRepository,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    private val tasksSettingsRepository: TasksSettingsRepository,
) : SimpleTool<ProjectReviewInput>(TypeToken.of(ProjectReviewInput::class.java), NAME, DESCRIPTION) {
    override suspend fun execute(args: ProjectReviewInput): String {
        val brief =
            args.projectId?.let { id ->
                ProjectBriefSupport.brief(
                    projectId = ProjectId(id),
                    taskRepository = taskRepository,
                    projectsRepository = projectsRepository,
                    notesRepository = notesRepository,
                    clock = clock,
                    zone = timeZoneProvider.current(),
                    // The user's threshold, so `stagnant` means the same set here as
                    // in the list tool and on the statistics screen.
                    stalenessDays = tasksSettingsRepository.stalenessThresholdDays.first(),
                )
            }
        if (args.projectId != null && brief == null) {
            // There is no such project — it was deleted, or the id is wrong. Saying
            // so is the only honest answer: a review of the name would be about a
            // project the app cannot see.
            return "No project with id ${args.projectId} exists."
        }
        val tasks =
            args.selection
                ?.let { selection ->
                    TaskPlanSupport.select(
                        selection = selection,
                        taskRepository = taskRepository,
                        clock = clock,
                        zone = timeZoneProvider.current(),
                        stalenessDays = tasksSettingsRepository.stalenessThresholdDays.first(),
                    )
                } ?: args.tasks
        val p =
            prompt(Prompt.Empty, KoogClock.System) {
                system(Prompts.projectReviewSystem)
                user(
                    brief
                        ?.let { Prompts.projectBriefUser(it) }
                        ?: Prompts.projectReviewUser(args.projectName, tasks),
                )
            }
        val response = promptExecutor.execute(p, model, emptyList())
        return extractText(response)
    }

    companion object {
        const val NAME = "project_review"
        const val DESCRIPTION =
            "Review a project, identify risks, and suggest the next action. With a projectId " +
                "the review is grounded in the project's own tasks, their health (fine, overdue, " +
                "blocked or stagnant) and the notes attached to them; without one it reviews the " +
                "task titles the caller names."

        private fun extractText(response: Message.Assistant): String =
            response.parts
                .filterIsInstance<MessagePart.Text>()
                .joinToString("") { it.text }
                .trim()
    }
}
