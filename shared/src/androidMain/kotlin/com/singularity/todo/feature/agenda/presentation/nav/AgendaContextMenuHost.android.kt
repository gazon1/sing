package com.singularity.todo.feature.agenda.presentation.nav

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.tasks.presentation.contextmenu.TaskContextMenuSheet
import com.singularity.todo.feature.tasks.presentation.model.TaskUi

/**
 * Android's task context menu: a bottom sheet, opened by a long press.
 *
 * The sheet hosts its own window, which does not inherit the
 * `testTagsAsResourceId` flag set at the app root — so the flag is re-asserted
 * here, the same way the shell's own bottom sheet does it.
 *
 * The menu's *content* is Android's: the sheet has no "share" row, and opening a
 * task is a row of its own. The intents it dispatches are the same ones the
 * Desktop menu dispatches.
 *
 * @return the host the agenda graph takes.
 */
fun androidAgendaContextMenuHost(): AgendaContextMenuHost = { taskUi: TaskUi, _, onDismiss, onIntent ->
    TaskContextMenuSheet(
        task = taskUi,
        onOpen = { onIntent(AgendaIntent.TaskClicked(taskUi.id)) },
        onToggleComplete = { onIntent(AgendaIntent.TaskCheckClicked(taskUi.id)) },
        onTogglePin = { onIntent(AgendaIntent.TaskPinClicked(taskUi.id)) },
        onArchive = { onIntent(AgendaIntent.TaskDeleteClicked(taskUi.id)) },
        onDismiss = onDismiss,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
    )
}
