package com.singularity.todo.feature.tasks.presentation.state

import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate

/**
 * Навигационный режим для [TaskDetailScreen].
 * Определяет, какой VM использовать и с какими параметрами.
 */
sealed interface TaskDetailMode {
    /** Режим просмотра существующей задачи. */
    data class View(val taskId: TaskId) : TaskDetailMode

    /** Режим создания новой задачи. */
    data class Create(val initialDueDate: LocalDate? = null) : TaskDetailMode
}
