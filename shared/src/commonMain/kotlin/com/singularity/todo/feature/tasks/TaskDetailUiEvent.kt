package com.singularity.todo.feature.tasks

/**
 * One-shot события экрана [TaskDetailScreen].
 *
 * Здесь ТОЛЬКО события, которые не являются частью потока состояния:
 * - [Saved] / [Error] — feedback после доменной операции
 * - [NavigateBack] — навигация
 * - [UndoDelete] — soft-delete с возможностью восстановления
 *
 * Открытие sheet / dialog — это **routing state экрана**, а не событие.
 * Routing идёт через [TaskDetailIntent.OpenSheet] → [ActiveSheet], без участия UiEvent.
 */
sealed interface TaskDetailUiEvent {

    /** Операция сохранена; показать snackbar "Saved". */
    data class Saved(val message: String) : TaskDetailUiEvent

    /** Ошибка; показать snackbar / dialog. */
    data class Error(val message: String) : TaskDetailUiEvent

    /** Экран должен выполнить навигацию назад. */
    data object NavigateBack : TaskDetailUiEvent

    /**
     * Задача мягко удалена; UI показывает snackbar с Undo.
     * Экран вызывает [TaskDetailViewModel.restore] при выборе Undo.
     */
    data class UndoDelete(val taskId: TaskId) : TaskDetailUiEvent
}
