package com.singularity.todo.feature.tasks

/**
 * Pure formatter for AI action results. Kept out of the Composable so it can be
 * unit-tested without launching a Compose runtime.
 *
 * Lives in the `tasks` package because it knows about [AiActionResult] — a
 * task-domain concept that core UI must not depend on.
 */
internal fun formatAiResult(result: AiActionResult): String = when (result) {
    is AiActionResult.RefineTitle -> "Refined title: ${result.newTitle}"
    is AiActionResult.GenerateDescription -> "Description: ${result.description}"
    is AiActionResult.GenerateChecklist -> "Checklist:\n" + result.steps.joinToString("\n") { "- $it" }
    is AiActionResult.DecomposeTask -> "Sub-tasks:\n" + result.subTasks.joinToString("\n") { "- $it" }
    is AiActionResult.PickTime -> "Suggested time: ${result.suggestedTime}"
    is AiActionResult.Error -> "Error: ${result.message}"
}
