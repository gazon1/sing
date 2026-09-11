package com.singularity.todo.feature.tasks.domain.model

import com.singularity.todo.feature.projects.ProjectId

sealed interface TasksUiEvent {
    data class AiResult(val message: String) : TasksUiEvent
    data class Error(val message: String) : TasksUiEvent
    data object NavigateBack : TasksUiEvent
}

sealed interface TaskDetailUiEvent {
    data class Error(val message: String) : TaskDetailUiEvent
    data class Saved(val message: String) : TaskDetailUiEvent
    data class UndoDelete(val taskId: TaskId) : TaskDetailUiEvent
    data object NavigateBack : TaskDetailUiEvent
}

sealed interface TaskEditorUiEvent {
    data object NavigateBack : TaskEditorUiEvent
    data class Error(val message: String) : TaskEditorUiEvent
}

/** Groups tasks by parent relationship for hierarchical display. */
sealed interface TaskGroup {
    data class TopLevel(
        val parent: Task,
        val children: List<Task>,
        val isExpanded: Boolean,
    ) : TaskGroup
    data class Child(val task: Task) : TaskGroup
}

sealed interface TasksUiState {
    data object Loading : TasksUiState
    data class Empty(val filter: TaskFilter) : TasksUiState
    data class Content(
        val filter: TaskFilter,
        val taskGroups: List<TaskGroup>,
        val selectedIds: Set<TaskId> = emptySet(),
    ) : TasksUiState
    data class Error(val message: String) : TasksUiState
}

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

sealed class TasksScreenEntry {
    data object FromToday : TasksScreenEntry()
    data object FromInbox : TasksScreenEntry()
    data class FromProject(val projectId: ProjectId) : TasksScreenEntry()
}

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
