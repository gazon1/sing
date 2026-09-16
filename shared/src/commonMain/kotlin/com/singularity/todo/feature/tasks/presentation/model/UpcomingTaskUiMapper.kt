package com.singularity.todo.feature.tasks.presentation.model

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.presentation.state.TaskBadgesUi
import com.singularity.todo.feature.tasks.presentation.state.UpcomingTaskUi
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Pure transformations from domain [Task] to UI models used in the Upcoming screen.
 *
 * No side effects, no dependencies — fully testable without mocks or Compose.
 */
object UpcomingTaskUiMapper {

    private val monthNames = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    /**
     * Converts a [Task] to [UpcomingTaskUi] for display in the Upcoming screen.
     *
     * [today] is used to compute [UpcomingTaskUi.isOverdue] and the human-readable
     * [UpcomingTaskUi.recurringLabel].
     *
     * [projectName] is set to the raw [com.singularity.todo.feature.projects.ProjectId.value];
     * the caller is responsible for enriching it with the resolved project name via
     * [UpcomingViewModel.projectNamesFlow].
     */
    fun toUpcomingTaskUi(task: Task, today: LocalDate): UpcomingTaskUi {
        val due = task.dueDate
        val isOverdue = !task.isCompleted && due != null && due < today
        val recurring = due?.let { labelFor(it, today) }
        return UpcomingTaskUi(
            id = task.id,
            title = task.title,
            isCompleted = task.isCompleted,
            isOverdue = isOverdue,
            isRecurring = recurring != null,
            recurringLabel = recurring,
            projectName = task.projectId?.value,
            badges = TaskBadgesUi(
                hasNote = task.description?.isNotBlank() == true,
                deadlineDate = due,
            ),
        )
    }

    /**
     * Returns a human-readable label for a due date, following the paste design:
     * - today → "Today"
     * - tomorrow → "Tomorrow"
     * - otherwise → "d MMM yyyy" (e.g. "17 September 2026")
     */
    private fun labelFor(due: LocalDate, today: LocalDate): String = when (due) {
        today -> "Today"
        today.plus(1, DateTimeUnit.DAY) -> "Tomorrow"
        else -> "${due.dayOfMonth} ${monthNames[due.month.ordinal]} ${due.year}"
    }
}
