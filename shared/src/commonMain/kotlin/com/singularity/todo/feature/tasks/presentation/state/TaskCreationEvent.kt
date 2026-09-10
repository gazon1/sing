package com.singularity.todo.feature.tasks.presentation.state

/**
 * Пользовательские интенты с экрана. Экран сам ничего не решает —
 * только эмитит события, вся логика в ViewModel/UseCase слое.
 */
sealed interface TaskCreationEvent {
    data object BackClicked : TaskCreationEvent
    data object MoreMenuClicked : TaskCreationEvent
    data object CheckboxToggled : TaskCreationEvent
    data class TitleChanged(val value: String) : TaskCreationEvent
    data class DescriptionChanged(val value: String) : TaskCreationEvent
    data object ChecklistClicked : TaskCreationEvent
    data object ProjectClicked : TaskCreationEvent
    data object PriorityClicked : TaskCreationEvent
    data object DueDateClicked : TaskCreationEvent
    data object DueDateCleared : TaskCreationEvent
    data object ReminderClicked : TaskCreationEvent
    data object RepeatClicked : TaskCreationEvent
    data object DeadlineClicked : TaskCreationEvent
    data object SubtasksClicked : TaskCreationEvent
    data object FilesClicked : TaskCreationEvent
    data object MoreOptionsClicked : TaskCreationEvent
    data object SaveClicked : TaskCreationEvent
}
