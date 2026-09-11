package com.singularity.todo.feature.tasks.presentation.state

/**
 * Bottom-sheet variants для экранов TaskDetail (View и Create).
 * Не содержит Delete/Archive — они есть только в View-режиме.
 */
sealed interface CreateActiveSheet {
    data object Date : CreateActiveSheet
    data object Time : CreateActiveSheet
    data object Priority : CreateActiveSheet
    data object Kind : CreateActiveSheet
    data object Project : CreateActiveSheet
    data object Tags : CreateActiveSheet
    data object Reminder : CreateActiveSheet
    data object Attachment : CreateActiveSheet
}
