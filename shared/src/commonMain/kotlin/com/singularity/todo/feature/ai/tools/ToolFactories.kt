package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.Prompt
import ai.koog.serialization.TypeToken
import ai.koog.utils.time.KoogClock
import com.singularity.todo.feature.ai.prompts.Prompts
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Factory functions for creating Koog [SimpleTool] instances.
 *
 * Instead of one class file per tool, use these inline factories.
 * The DI module wires them directly.
 */

/**
 * Creates an LLM-powered tool that calls [PromptExecutor] with a system + user prompt
 * and parses the text response into [O].
 *
 * Usage:
 * ```
 * factory { llmTool<RefineTaskInput, RefineTaskOutput>(
 *     name = "refine_task",
 *     description = "...",
 *     systemPrompt = Prompts.refineSystem,
 *     userPrompt = { args: RefineTaskInput -> Prompts.refineUser(args.currentTitle, args.description) },
 *     outputSerializer = RefineTaskOutput.serializer(),
 * ) }
 * ```
 */
inline fun <reified I : @Serializable Any, reified O : @Serializable Any> llmTool(
    name: String,
    description: String,
    systemPrompt: String,
    noinline userPrompt: (I) -> String,
    outputSerializer: KSerializer<O>,
    noinline outputBlock: (String) -> O = { text -> Json.decodeFromString(text) },
): (PromptExecutor, LLModel) -> SimpleTool<I> = { promptExecutor, model ->
    object : SimpleTool<I>(TypeToken.of(I::class.java), name, description) {
        override suspend fun execute(args: I): String {
            val p = prompt(Prompt.Empty, KoogClock.System) {
                system(systemPrompt)
                user(userPrompt(args))
            }
            val response = promptExecutor.execute(p, model, emptyList())
            val text = extractText(response)
            return try {
                Json.encodeToString(outputSerializer, outputBlock(text))
            } catch (_: Exception) {
                // Fallback: try direct decode
                Json.encodeToString(outputSerializer, outputBlock(text))
            }
        }

        private fun extractText(response: Message.Assistant): String =
            response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()
    }
}

/**
 * Creates a data-only tool that reads from repositories — no LLM call.
 *
 * Usage:
 * ```
 * factory { dataTool<GetTaskInput, GetTaskOutput>(
 *     name = "get_task",
 *     description = "...",
 *     block = { args -> ... return Json.encodeToString(...) }
 * ) }
 * ```
 */
inline fun <reified I : @Serializable Any, reified O : @Serializable Any> dataTool(
    name: String,
    description: String,
    noinline block: suspend (I) -> String,
): () -> SimpleTool<I> = {
    object : SimpleTool<I>(TypeToken.of(I::class.java), name, description) {
        override suspend fun execute(args: I): String = block(args)
    }
}

// ─── Pre-built LLM tool factories ──────────────────────────────────────────────

fun llmRefineTaskTool() = llmTool<RefineTaskInput, RefineTaskOutput>(
    name = "refine_task",
    description = "Rewrite the task title to be clearer and more actionable.",
    systemPrompt = Prompts.refineSystem,
    userPrompt = { args: RefineTaskInput -> Prompts.refineUser(args.currentTitle, args.description) },
    outputSerializer = RefineTaskOutput.serializer(),
)

fun llmSmartRewriteTool() = llmTool<SmartRewriteInput, SmartRewriteOutput>(
    name = "smart_rewrite",
    description = "Rewrite a raw idea into a concise, actionable task title.",
    systemPrompt = Prompts.smartRewriteSystem,
    userPrompt = { args: SmartRewriteInput -> Prompts.smartRewriteUser(args.rawIdea) },
    outputSerializer = SmartRewriteOutput.serializer(),
)

fun llmGenerateDescriptionTool() = llmTool<GenerateDescriptionInput, GenerateDescriptionOutput>(
    name = "generate_description",
    description = "Write a brief description for a task.",
    systemPrompt = Prompts.generateDescriptionSystem,
    userPrompt = { args: GenerateDescriptionInput -> Prompts.generateDescriptionUser(args.title) },
    outputSerializer = GenerateDescriptionOutput.serializer(),
)

fun llmDecomposeTaskTool() = llmTool<DecomposeTaskInput, DecomposeTaskOutput>(
    name = "decompose_task",
    description = "Break a task into smaller, actionable sub-tasks.",
    systemPrompt = Prompts.decomposeTaskSystem,
    userPrompt = { args: DecomposeTaskInput -> Prompts.decomposeTaskUser(args.title, args.description) },
    outputSerializer = DecomposeTaskOutput.serializer(),
    outputBlock = { text ->
        try {
            Json.decodeFromString<DecomposeTaskOutput>(text)
        } catch (_: Exception) {
            // Fallback: parse as lines
            val lines = text.lines()
                .filter { it.isNotBlank() && !it.startsWith("[") && !it.startsWith("]") }
                .map { it.trim().removePrefix("- ").removePrefix("* ").removeSurrounding("\"") }
            DecomposeTaskOutput(lines)
        }
    },
)

fun llmGenerateChecklistTool() = llmTool<GenerateChecklistInput, GenerateChecklistOutput>(
    name = "generate_checklist",
    description = "Generate a checklist of steps for a task.",
    systemPrompt = Prompts.generateChecklistSystem,
    userPrompt = { args: GenerateChecklistInput -> Prompts.generateChecklistUser(args.title, args.description) },
    outputSerializer = GenerateChecklistOutput.serializer(),
)

fun llmPickTimeTool() = llmTool<PickTimeInput, PickTimeOutput>(
    name = "pick_time",
    description = "Suggest the best time slot for a task.",
    systemPrompt = Prompts.pickTimeSystem,
    userPrompt = { args: PickTimeInput -> Prompts.pickTimeUser(args.title, args.description) },
    outputSerializer = PickTimeOutput.serializer(),
)

fun llmClusterTasksTool() = llmTool<ClusterTasksInput, ClusterTasksOutput>(
    name = "cluster_tasks",
    description = "Group tasks into thematic clusters.",
    systemPrompt = Prompts.clusterTasksSystem,
    userPrompt = { args: ClusterTasksInput -> Prompts.clusterTasksUser(args.tasks) },
    outputSerializer = ClusterTasksOutput.serializer(),
)

fun llmClusterNotesTool() = llmTool<ClusterNotesInput, ClusterNotesOutput>(
    name = "cluster_notes",
    description = "Group notes into thematic clusters.",
    systemPrompt = Prompts.clusterNotesSystem,
    userPrompt = { args: ClusterNotesInput -> Prompts.clusterNotesUser(args.notes) },
    outputSerializer = ClusterNotesOutput.serializer(),
)

/**
 * ProjectReviewTool returns raw text — not wrapped in a serializable output type.
 * Kept as a direct class reference rather than the generic factory.
 */
fun llmWeeklyPlanTool() = llmTool<WeeklyPlanInput, WeeklyPlanOutput>(
    name = "weekly_plan",
    description = "Suggest 3-5 tasks to focus on this week.",
    systemPrompt = Prompts.weeklyPlanSystem,
    userPrompt = { args: WeeklyPlanInput -> Prompts.weeklyPlanUser(args.tasks) },
    outputSerializer = WeeklyPlanOutput.serializer(),
    outputBlock = { text ->
        try {
            Json.decodeFromString<WeeklyPlanOutput>(text)
        } catch (_: Exception) {
            val lines = text.lines()
                .filter { it.isNotBlank() && !it.startsWith("[") && !it.startsWith("]") }
                .map { it.trim().removePrefix("- ").removePrefix("* ").removeSurrounding("\"") }
            WeeklyPlanOutput(lines)
        }
    },
)

fun llmProjectReviewTool(promptExecutor: PromptExecutor, model: LLModel) = object : SimpleTool<ProjectReviewInput>(
    TypeToken.of(ProjectReviewInput::class.java), "project_review",
    "Review a project and suggest next actions."
) {
    override suspend fun execute(args: ProjectReviewInput): String {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(Prompts.projectReviewSystem)
            user(Prompts.projectReviewUser(args.projectName, args.tasks))
        }
        val response = promptExecutor.execute(p, model, emptyList())
        return response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()
    }
}

// ─── Data tool factories ────────────────────────────────────────────────────────

fun dataGetNoteTool(notesRepo: com.singularity.todo.feature.notes.NotesRepository) = dataTool<GetNoteInput, GetNoteOutput>(
    name = "get_note",
    description = "Fetch a single note by its ID.",
    block = { args ->
        val note = notesRepo.watchNote(com.singularity.todo.feature.notes.NoteId.fromString(args.noteId)).first()
        val output = if (note != null) {
            GetNoteOutput(note.id.value, note.title, note.bodyMarkdown)
        } else {
            GetNoteOutput(args.noteId, "(not found)", null)
        }
        Json.encodeToString(GetNoteOutput.serializer(), output)
    },
)

fun dataGetTaskTool(taskRepository: com.singularity.todo.feature.tasks.TaskRepository) = dataTool<GetTaskInput, GetTaskOutput>(
    name = "get_task",
    description = "Fetch a single task by its ID.",
    block = { args ->
        val task = taskRepository.watchTask(com.singularity.todo.feature.tasks.TaskId(args.taskId)).first()
        val output = if (task != null) {
            GetTaskOutput(task.id.value, task.title, task.description, task.isCompleted, task.projectId?.value)
        } else {
            GetTaskOutput(args.taskId, "(not found)", null, false, null)
        }
        Json.encodeToString(GetTaskOutput.serializer(), output)
    },
)

fun dataGetProjectTool(projectsRepository: com.singularity.todo.feature.projects.ProjectsRepository) = dataTool<GetProjectInput, GetProjectOutput>(
    name = "get_project",
    description = "Fetch a single project by its ID.",
    block = { args ->
        val project = projectsRepository.watchProject(com.singularity.todo.feature.projects.ProjectId(args.projectId)).first()
        val output = if (project != null) {
            GetProjectOutput(project.id.value, project.name, project.description)
        } else {
            GetProjectOutput(args.projectId, "(not found)", null)
        }
        Json.encodeToString(GetProjectOutput.serializer(), output)
    },
)

fun dataListTasksTool(taskRepository: com.singularity.todo.feature.tasks.TaskRepository) = dataTool<ListTasksInput, ListTasksOutput>(
    name = "list_tasks",
    description = "List tasks, optionally filtered by project.",
    block = { args ->
        val userId = com.singularity.todo.feature.tasks.UserId(args.userId.ifBlank { "local-user" })
        val filter = args.projectId?.let {
            com.singularity.todo.feature.tasks.TaskFilter.ByProject(com.singularity.todo.feature.projects.ProjectId(it))
        } ?: com.singularity.todo.feature.tasks.TaskFilter.All
        val tasks = taskRepository.watchTasks(userId, filter).first().take(args.limit)
            .map { TaskSummary(it.id.value, it.title, it.isCompleted, it.projectId?.value) }
        Json.encodeToString(ListTasksOutput.serializer(), ListTasksOutput(tasks))
    },
)

fun dataSearchTasksTool(taskRepository: com.singularity.todo.feature.tasks.TaskRepository) = dataTool<SearchTasksInput, SearchTasksOutput>(
    name = "search_tasks",
    description = "Search tasks by title (case-insensitive).",
    block = { args ->
        val tasks = taskRepository.watchTasks(
            com.singularity.todo.feature.tasks.UserId(args.userId),
            com.singularity.todo.feature.tasks.TaskFilter.All
        ).first()
            .filter { it.title.contains(args.query, ignoreCase = true) }
            .take(args.limit)
            .map { TaskSummary(it.id.value, it.title, it.isCompleted, it.projectId?.value) }
        Json.encodeToString(SearchTasksOutput.serializer(), SearchTasksOutput(tasks))
    },
)

fun dataListLinkedTasksTool(taskRepository: com.singularity.todo.feature.tasks.TaskRepository) = dataTool<ListLinkedTasksInput, ListLinkedTasksOutput>(
    name = "list_linked_tasks",
    description = "List all tasks linked to a specific project.",
    block = { args ->
        val tasks = taskRepository.watchTasks(
            com.singularity.todo.feature.tasks.UserId(args.userId),
            com.singularity.todo.feature.tasks.TaskFilter.ByProject(com.singularity.todo.feature.projects.ProjectId(args.projectId))
        ).first()
            .map { TaskSummary(it.id.value, it.title, it.isCompleted, it.projectId?.value) }
        Json.encodeToString(ListLinkedTasksOutput.serializer(), ListLinkedTasksOutput(tasks))
    },
)
