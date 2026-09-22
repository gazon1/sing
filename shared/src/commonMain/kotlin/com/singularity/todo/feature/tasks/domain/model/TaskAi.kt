package com.singularity.todo.feature.tasks.domain.model

/** One-shot AI action results shown to user */
sealed interface AiActionResult {
    data class RefineTitle(val newTitle: String) : AiActionResult
    data class GenerateDescription(val description: String) : AiActionResult
    data class GenerateChecklist(val steps: List<String>) : AiActionResult
    data class DecomposeTask(val subTasks: List<String>) : AiActionResult
    data class PickTime(val suggestedTime: String) : AiActionResult
    data class Error(val message: String) : AiActionResult
}

/** Stable, value-classified set of actions a user can trigger from the AI sheet. */
enum class TaskAiAction { RefineTitle, GenerateDescription, GenerateChecklist, Decompose, SuggestTime }

/**
 * Pure formatter for AI action results.
 */
internal fun formatAiResult(result: AiActionResult): String = when (result) {
    is AiActionResult.RefineTitle -> "Refined title: ${result.newTitle}"
    is AiActionResult.GenerateDescription -> "Description: ${result.description}"
    is AiActionResult.GenerateChecklist -> "Checklist:\n" + result.steps.joinToString("\n") { "- $it" }
    is AiActionResult.DecomposeTask -> "Sub-tasks:\n" + result.subTasks.joinToString("\n") { "- $it" }
    is AiActionResult.PickTime -> "Suggested time: ${result.suggestedTime}"
    is AiActionResult.Error -> "Error: ${result.message}"
}
