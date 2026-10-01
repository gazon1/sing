package com.singularity.todo.feature.tasks.presentation.state

import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * User intents for the task creation screen.
 */
sealed interface TaskCreateIntent : MviIntent {
    data class TitleChanged(val title: String) : TaskCreateIntent
    data class DescriptionChanged(val description: String) : TaskCreateIntent
    data class SetPriority(val priority: com.singularity.todo.feature.tasks.domain.model.TaskPriority) :
        TaskCreateIntent
    data class SetDueDate(val date: LocalDate?) : TaskCreateIntent
    data class SetDueTime(val time: LocalTime?) : TaskCreateIntent
    data object DueDateCleared : TaskCreateIntent
    data class SetStartDate(val date: LocalDate?) : TaskCreateIntent
    data class SetStartTime(val time: LocalTime?) : TaskCreateIntent
    data class SetEndDate(val date: LocalDate?) : TaskCreateIntent
    data class SetEndTime(val time: LocalTime?) : TaskCreateIntent
    data class SetProject(val projectId: ProjectId?) : TaskCreateIntent
    data class SetTags(val tagIds: List<TagId>) : TaskCreateIntent
    data class SetRecurrence(val spec: RecurrenceSpec?) : TaskCreateIntent
    data object PinToggled : TaskCreateIntent
    data class AddChecklistItem(val text: String) : TaskCreateIntent
    data class ToggleChecklistItem(val id: String) : TaskCreateIntent
    data class RemoveChecklistItem(val id: String) : TaskCreateIntent
    data class AddAttachmentUrl(val url: String, val title: String?) : TaskCreateIntent
    data class RemoveAttachmentUrl(val url: String) : TaskCreateIntent
    data object SaveClicked : TaskCreateIntent

    /** Clear the current inline / snackbar error emitted via [TaskCreateUiState.error]. */
    data object DismissError : TaskCreateIntent
}
