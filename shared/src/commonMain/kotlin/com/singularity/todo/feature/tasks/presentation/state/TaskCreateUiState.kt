package com.singularity.todo.feature.tasks.presentation.state

import androidx.compose.runtime.Immutable
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate

/**
 * Immutable UI state экрана создания задачи.
 */
@Immutable
data class TaskCreateUiState(
    val draft: TaskDraft = TaskDraft(),
    val isSaveEnabled: Boolean = false,
    val error: String? = null,
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
)

/**
 * Черновик создаваемой задачи.
 */
@Immutable
data class TaskDraft(
    val title: String = "",
    val description: String = "",
    val priority: TaskPriority = TaskPriority.None,
    val dueDate: DueDateOption = DueDateOption.None,
    val dueTime: String? = null, // "HH:mm"
    val projectId: String? = null,
    val tagIds: List<String> = emptyList(),
)

/**
 * Варианты выбора даты для создания задачи.
 */
sealed interface DueDateOption {
    data object None : DueDateOption
    data object Today : DueDateOption
    data object Tomorrow : DueDateOption
    data class Custom(val date: LocalDate, val label: String) : DueDateOption
}
