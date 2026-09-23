package com.singularity.todo.feature.tasks.presentation.state

import androidx.compose.runtime.Immutable
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

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
 * Persisted via [DraftStore] across process death.
 */
@Serializable
@Immutable
data class TaskDraft(
    val title: String = "",
    val description: String = "",
    val priority: TaskPriority = TaskPriority.None,
    val dueDate: DueDateOption = DueDateOption.None,
    val dueTime: LocalTime? = null,
    val startDate: DueDateOption = DueDateOption.None,
    val startTime: LocalTime? = null,
    val endDate: DueDateOption = DueDateOption.None,
    val endTime: LocalTime? = null,
    val accentColor: Long? = null,
    val emoji: String? = null,
    val projectId: String? = null,
    val tagIds: List<String> = emptyList(),
)

/**
 * Варианты выбора даты для создания задачи.
 */
@Serializable
sealed interface DueDateOption {
    @Serializable data object None : DueDateOption

    @Serializable data object Today : DueDateOption

    @Serializable data object Tomorrow : DueDateOption

    @Serializable data class Custom(val date: LocalDate, val label: String) : DueDateOption
}
