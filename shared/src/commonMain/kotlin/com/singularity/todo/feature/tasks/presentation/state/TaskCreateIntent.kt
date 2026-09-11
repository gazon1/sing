package com.singularity.todo.feature.tasks.presentation.state

import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * User intents для экрана создания задачи.
 */
sealed interface TaskCreateIntent {
    data class TitleChanged(val title: String) : TaskCreateIntent
    data class DescriptionChanged(val description: String) : TaskCreateIntent
    data class SetPriority(val priority: TaskPriority) : TaskCreateIntent
    data class SetDueDate(val date: LocalDate?) : TaskCreateIntent
    data class SetDueTime(val time: LocalTime?) : TaskCreateIntent
    data object DueDateCleared : TaskCreateIntent
    data object SaveClicked : TaskCreateIntent
    data object DiscardChanges : TaskCreateIntent
}
