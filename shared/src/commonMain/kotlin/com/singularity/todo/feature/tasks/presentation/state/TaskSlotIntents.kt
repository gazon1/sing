package com.singularity.todo.feature.tasks.presentation.state

import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.feature.timetracking.domain.TimeEntryKind

sealed interface TaskDetailSlotIntent : MviIntent

sealed interface TaskEntityIntent : TaskDetailSlotIntent

sealed interface TaskDraftIntent : TaskDetailSlotIntent

sealed interface TaskCompletionIntent : TaskDetailSlotIntent

sealed interface TaskChildrenIntent : TaskDetailSlotIntent

sealed interface TaskRemindersIntent : TaskDetailSlotIntent

sealed interface TaskLifecycleIntent : TaskDetailSlotIntent

sealed interface TaskAiIntent : TaskDetailSlotIntent

sealed interface TaskTimeSlotIntent : TaskDetailSlotIntent {
    data object Start : TaskTimeSlotIntent
    data object Stop : TaskTimeSlotIntent
    data class CreateManual(val startedAtMs: Long, val endedAtMs: Long, val kind: TimeEntryKind, val note: String?) :
        TaskTimeSlotIntent
    data class Tick(val elapsedMs: Long) : TaskTimeSlotIntent
}
