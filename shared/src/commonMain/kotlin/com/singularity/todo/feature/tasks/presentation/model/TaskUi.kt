package com.singularity.todo.feature.tasks.presentation.model

/**
 * Иммутабельная UI-модель задачи.
 *
 * Специально отделена от доменной/сетевой модели: экран не должен знать,
 * откуда пришли данные (Room / API / DataStore) — только то, что нужно
 * для отрисовки строки списка.
 *
 * Поле [isOverdue] вычисляется во ViewModel на основе [dueLabel] (или
 * оригинальной Date), чтобы UI-слой оставался чистым от логики дат.
 */
data class TaskUi(
    val id: Long,
    val title: String,
    val project: String?,          // null -> "Без проекта"
    val dueLabel: String?,         // уже отформатированная дата: "Сб, 05 сент 2026"
    val isRecurring: Boolean = false,
    val priority: TaskPriority = TaskPriority.NONE,
    val isCompleted: Boolean = false,
    val isOverdue: Boolean = false, // вычисляется во VM; просроченная невыполненная задача
    val isSelected: Boolean = false,
)

enum class TaskPriority {
    NONE, LOW, MEDIUM, HIGH;

    val isActive: Boolean get() = this != NONE
}

/** Счётчики для заголовка/фильтров — вычисляются из списка один раз. */
data class TaskListStats(
    val total: Int,
    val active: Int,
    val completed: Int,
) {
    companion object {
        fun from(tasks: List<TaskUi>): TaskListStats = TaskListStats(
            total = tasks.size,
            active = tasks.count { !it.isCompleted },
            completed = tasks.count { it.isCompleted },
        )
    }
}

/** Фильтр списка. ALL = видим всё, ACTIVE = только невыполненные, COMPLETED = только выполненные. */
enum class TaskListFilter { ALL, ACTIVE, COMPLETED }
