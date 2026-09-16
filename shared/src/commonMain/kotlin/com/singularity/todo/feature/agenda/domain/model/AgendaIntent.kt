package com.singularity.todo.feature.agenda.domain.model

import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * User intents (events) on the Agenda screen.
 */
sealed interface AgendaIntent {
    data class TaskClicked(val taskId: TaskId) : AgendaIntent
    data class TaskCheckClicked(val taskId: TaskId) : AgendaIntent
    data class TaskLongClicked(val taskId: TaskId) : AgendaIntent
    data class TaskPinClicked(val taskId: TaskId) : AgendaIntent
    data class TaskDeleteClicked(val taskId: TaskId) : AgendaIntent
    data class TaskExpandClicked(val taskId: TaskId) : AgendaIntent
}
