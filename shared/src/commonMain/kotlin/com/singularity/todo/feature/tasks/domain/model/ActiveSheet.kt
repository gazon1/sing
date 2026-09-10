package com.singularity.todo.feature.tasks.domain.model

sealed interface ActiveSheet {
    data object Date : ActiveSheet
    data object Time : ActiveSheet
    data object Priority : ActiveSheet
    data object Kind : ActiveSheet
    data object Project : ActiveSheet
    data object Tags : ActiveSheet
    data object Reminder : ActiveSheet
    data object Attachment : ActiveSheet
    data object Delete : ActiveSheet
    data object Archive : ActiveSheet
}
