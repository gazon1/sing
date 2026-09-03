package com.singularity.todo.feature.ai.prompts

/**
 * Loads AI system prompts from resource files at runtime.
 *
 * Resource files live in `shared/src/commonMain/resources/prompts/`.
 * In a KMP project, these are packaged into the JVM/Android classpath
 * and accessible via `require()` on JVM and Android targets.
 *
 * Falls back to embedded defaults if resources are not available (e.g. in test
 * environments where resources are not packaged).
 */
object PromptResources {

    private const val BASE = "prompts/"

    val refineSystem: String get() = load("refine_system.txt")
    val smartRewriteSystem: String get() = load("smart_rewrite_system.txt")
    val generateDescriptionSystem: String get() = load("generate_description_system.txt")
    val decomposeTaskSystem: String get() = load("decompose_task_system.txt")
    val generateChecklistSystem: String get() = load("generate_checklist_system.txt")
    val pickTimeSystem: String get() = load("pick_time_system.txt")
    val clusterTasksSystem: String get() = load("cluster_tasks_system.txt")
    val clusterNotesSystem: String get() = load("cluster_notes_system.txt")
    val weeklyPlanSystem: String get() = load("weekly_plan_system.txt")
    val projectReviewSystem: String get() = load("project_review_system.txt")
    val chatSystem: String get() = load("chat_system.txt")

    private fun load(name: String): String {
        return try {
            val resource = object {}.javaClass.getResourceAsStream("/$BASE$name")
            if (resource != null) {
                resource.bufferedReader().readText()
            } else {
                fallbackPrompt(name)
            }
        } catch (e: Exception) {
            fallbackPrompt(name)
        }
    }

    private fun fallbackPrompt(name: String): String = when (name) {
        "refine_system.txt" -> """
            You are a productivity assistant. Rewrite the user's task title to be clearer,
            more actionable, and specific. Return only the rewritten title, no quotes.
        """.trimIndent()
        "smart_rewrite_system.txt" -> """
            You are an expert at crafting concise, actionable task titles from raw ideas.
            Return only the rewritten title — no explanation, no quotes, no markdown.
        """.trimIndent()
        "generate_description_system.txt" -> """
            You are a productivity assistant. Given a task title, write a brief, clear
            description (1-2 sentences) that adds useful context. Return only the description.
        """.trimIndent()
        "decompose_task_system.txt" -> """
            You are a task decomposition assistant. Break the given task into 3-7 smaller,
            actionable sub-tasks. Return a JSON array of strings, each being one sub-task.
            No numbering, no markdown, just the array.
        """.trimIndent()
        "generate_checklist_system.txt" -> """
            You are a checklist generation assistant. Given a task description, generate
            a checklist of 3-8 concrete steps to complete it. Return a JSON array of
            strings, each step on its own line. No markdown.
        """.trimIndent()
        "pick_time_system.txt" -> """
            You are a scheduling assistant. Given a task title and optional description,
            suggest the best time slot for today or tomorrow. Return only a time like
            "14:00" or "tomorrow 09:00". If no time can be determined, return "no-time".
        """.trimIndent()
        "cluster_tasks_system.txt" -> """
            You are a task categorization assistant. Group the given task titles into
            2-5 thematic clusters. Return a JSON object where keys are cluster names and
            values are arrays of task titles. No explanation.
        """.trimIndent()
        "cluster_notes_system.txt" -> """
            You are a notes categorization assistant. Group the given note titles into
            2-5 thematic clusters. Return a JSON object where keys are cluster names and
            values are arrays of note titles. No explanation.
        """.trimIndent()
        "weekly_plan_system.txt" -> """
            You are a weekly planning assistant. Given the user's current tasks, suggest
            which 3-5 tasks to focus on this week. Return a JSON array of task titles,
            ordered by priority. Keep it realistic for one week.
        """.trimIndent()
        "project_review_system.txt" -> """
            You are a project health reviewer. Given a project name and task list, identify
            the biggest risks and suggest one concrete next action. Return a short paragraph.
        """.trimIndent()
        "chat_system.txt" -> """
            You are a helpful, concise productivity assistant inside Singularity.
            You help users manage tasks, notes, and projects. Be direct and actionable.
        """.trimIndent()
        else -> error("Unknown prompt: $name")
    }
}
