package com.singularity.todo.feature.tasks.presentation.state

/**
 * Immutable UI state экрана создания задачи.
 * Держим примитивы/значения, а не Compose-специфичные типы —
 * это общий (commonMain) state для KMP, ViewModel им управляет.
 */
data class TaskCreationState(
    val title: String = "",
    val description: String = "",
    val isCompleted: Boolean = false,
    val checklistCount: Int = 0,
    val projectName: String? = null, // null = "Без проекта"
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val dueDate: DueDateOption = DueDateOption.Today,
    val reminder: String? = null,
    val repeatRule: String? = null,
    val deadline: String? = null,
    val subtasksCount: Int = 0,
    val attachmentsCount: Int = 0,
    val isSaveEnabled: Boolean = false
)

enum class TaskPriority(val label: String) {
    LOW("Низкий приоритет"),
    MEDIUM("Средний приоритет"),
    HIGH("Высокий приоритет")
}

sealed interface DueDateOption {
    data object None : DueDateOption
    data object Today : DueDateOption
    data object Tomorrow : DueDateOption
    data class Custom(val label: String) : DueDateOption
}
