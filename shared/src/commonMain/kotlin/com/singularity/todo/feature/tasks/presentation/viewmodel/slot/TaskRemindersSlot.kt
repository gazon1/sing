package com.singularity.todo.feature.tasks.presentation.viewmodel.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.core.ui.featureSlot.FeatureSlot
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderType
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.dueInstant
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.state.TaskRemindersIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Reminders for the task, including the platform alarm that fires them.
 *
 * A reminder only exists if two writes agree: the row in the database and the alarm in
 * `ReminderScheduler`. Setting one writes the row then schedules the alarm; clearing one
 * cancels the alarm then drops the row. Doing it in that order means a crash between the
 * two leaves a harmless orphan (an unscheduled row) rather than an alarm that fires with
 * nothing behind it.
 *
 * [ReminderOffset.AT_DUE] is the "no reminder" selection rather than a fire time — it maps
 * to clearing, which is why it is handled as a distinct branch.
 */
class TaskRemindersSlot(
    private val taskId: TaskId,
    private val deps: TaskDetailDeps,
    private val scope: AutoCloseableCoroutineScope,
    taskFlow: StateFlow<Task?>,
    private val onError: (String) -> Unit,
) : FeatureSlot<TaskRemindersState, TaskRemindersIntent> {

    private val _state = MutableStateFlow(TaskRemindersState())
    override val state: StateFlow<TaskRemindersState> = _state.asStateFlow()

    private val parentTask: StateFlow<Task?> = taskFlow

    init {
        scope.launch {
            deps.reminderRepo.watchByTask(taskId).collect { reminders ->
                _state.value = TaskRemindersState(reminders = reminders)
            }
        }
    }

    override fun onIntent(intent: TaskRemindersIntent) {
        when (intent) {
            is TaskDetailIntent.Domain.SetReminder -> setReminder(intent.offset)
            TaskDetailIntent.Domain.DeleteReminder -> clearReminder()
        }
    }

    private fun setReminder(offset: ReminderOffset) = scope.launch {
        val task = parentTask.value ?: return@launch
        if (offset == ReminderOffset.AT_DUE) {
            clearReminders(task)
            return@launch
        }
        val userId = deps.taskRepo.currentUserId()
        val now = deps.clock.now().toEpochMilliseconds()
        val fireAt = task.dueDate?.let { due ->
            dueInstant(due, task.dueTime, offset, deps.timeZoneProvider.current())
        } ?: now
        val reminder = Reminder(
            id = ReminderId.generate(),
            taskId = task.id,
            userId = userId,
            type = ReminderType.Gentle,
            offsetMinutes = -offset.minutes,
            fireAt = fireAt,
            recurringPattern = null,
        )
        deps.reminderRepo.upsert(reminder)
            .onSuccess { deps.reminderScheduler.schedule(reminder) }
            .onFailure { onError("Failed to set reminder") }
    }

    private fun clearReminder() = scope.launch {
        val task = parentTask.value ?: return@launch
        clearReminders(task)
    }

    private suspend fun clearReminders(task: Task) {
        val userId = deps.taskRepo.currentUserId()
        deps.reminderScheduler.cancelByTask(task.id, userId)
        deps.reminderRepo.deleteByTask(task.id)
            .onFailure { onError("Failed to remove reminder") }
    }
}
