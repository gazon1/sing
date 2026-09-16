package com.singularity.todo.feature.tasks.presentation.state

import androidx.compose.runtime.Immutable
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate

/**
 * UI badges for [UpcomingTaskRow].
 *
 * [deadlineDate] is set to [com.singularity.todo.feature.tasks.domain.model.Task.dueDate]
 * as a placeholder — a separate "hard deadline" field does not exist in the domain model yet.
 */
@Immutable
data class TaskBadgesUi(
    val hasNote: Boolean = false,
    val checklistDone: Int? = null,
    val checklistTotal: Int? = null,
    val progressPercent: Int? = null,
    val timerMinutes: Int? = null,
    /** Placeholder: set to task.dueDate. Realised as a separate field in a future MR. */
    val deadlineDate: LocalDate? = null,
)

/**
 * UI model for a task rendered in the Upcoming screen.
 *
 * [isRecurring] is derived in [UpcomingTaskUiMapper] — the domain [com.singularity.todo.feature.tasks.domain.model.Task]
 * has no `isRecurring` field.
 *
 * [projectName] is enriched by [UpcomingViewModel] from [com.singularity.todo.feature.projects.ProjectsRepository].
 */
@Immutable
data class UpcomingTaskUi(
    val id: TaskId,
    val title: String,
    val isCompleted: Boolean,
    val isOverdue: Boolean,
    val isRecurring: Boolean,
    /** Human-readable recurrence label: "Today", "Tomorrow", or "d MMM yyyy". */
    val recurringLabel: String?,
    /** Enriched by [UpcomingViewModel]. May be an ID string before enrichment. */
    val projectName: String?,
    val badges: TaskBadgesUi,
)

/**
 * UI state for the Upcoming screen.
 */
@Immutable
sealed interface UpcomingUiState {
    data object Loading : UpcomingUiState

    data class Content(
        val selectedDate: LocalDate,
        /** Monday of the week window currently displayed in the day picker. */
        val windowStart: LocalDate,
        val tasks: List<UpcomingTaskUi>,
    ) : UpcomingUiState
}

/** One-shot intents dispatched by the UI. */
sealed interface UpcomingIntent {
    data class SelectDate(val date: LocalDate) : UpcomingIntent
    data object NextWeek : UpcomingIntent
    data object PrevWeek : UpcomingIntent
    data class ToggleTask(val id: TaskId) : UpcomingIntent
}
