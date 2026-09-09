package com.singularity.todo.feature.tasks

/**
 * Routing state экрана [TaskDetailScreen]: какой bottom sheet или dialog открыт.
 *
 * Routing ведётся через [TaskDetailIntent.OpenSheet] → [ActiveSheet] в экране
 * (не через [TaskDetailUiEvent] → [toActiveSheet]).
 *
 * Экран сам владеет этим состоянием; VM ничего не знает про [ActiveSheet].
 */
sealed interface ActiveSheet {
    data object Date : ActiveSheet
    data object Time : ActiveSheet
    data object Priority : ActiveSheet
    data object Project : ActiveSheet
    data object Tags : ActiveSheet
    data object Reminder : ActiveSheet
    data object Attachment : ActiveSheet
    data object Kind : ActiveSheet
    data object ConfirmDelete : ActiveSheet
    data object ConfirmArchive : ActiveSheet
}
