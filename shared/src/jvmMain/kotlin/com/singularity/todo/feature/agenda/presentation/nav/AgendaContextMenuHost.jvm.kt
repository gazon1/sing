package com.singularity.todo.feature.agenda.presentation.nav

import androidx.compose.runtime.remember
import com.singularity.todo.core.files.SharePort
import com.singularity.todo.core.ui.menu.ContextMenuHost
import com.singularity.todo.core.ui.menu.ContextMenuOpenState
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.tasks.presentation.contextmenu.TaskMenuActions
import com.singularity.todo.feature.tasks.presentation.contextmenu.buildTaskContextMenu
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import org.koin.compose.koinInject

/**
 * Desktop's task context menu: a popup, opened by a right click.
 *
 * A separate function rather than an inline lambda in the agenda graph because
 * the body is the menu's whole contract — which intents each action dispatches —
 * and it reads as a unit rather than as an argument of the navigation graph.
 *
 * @return the host the agenda graph takes.
 */
fun desktopAgendaContextMenuHost(): AgendaContextMenuHost = { taskUi: TaskUi, offset, onDismiss, onIntent ->
    // Share is a platform action, so the port is resolved here rather than
    // pushed through the ViewModel. Keyed on the task because the shared text
    // is the task's own title and description.
    val sharePort: SharePort = koinInject()
    val menuActions = remember(taskUi) {
        TaskMenuActions(
            onTogglePin = { onIntent(AgendaIntent.TaskPinClicked(taskUi.id)) },
            onToggleComplete = { onIntent(AgendaIntent.TaskCheckClicked(taskUi.id)) },
            onDelete = { onIntent(AgendaIntent.TaskDeleteClicked(taskUi.id)) },
            onToggleExpand = { onIntent(AgendaIntent.TaskExpandClicked(taskUi.id)) },
            // null, not an empty lambda. TaskMenuBuilder enables the AI submenu from
            // `onAiAction != null`, so an empty body here turned "AI actions are
            // deferred" into a visible, enabled submenu whose five items did
            // nothing when picked. Android already leaves this null.
            onAiAction = null,
            // Archive and Delete are the same write here (softDelete); both
            // items stay because the list and the archive screen each name
            // that write in the user's own vocabulary.
            onArchive = { onIntent(AgendaIntent.TaskDeleteClicked(taskUi.id)) },
            onShare = { sharePort.shareText(taskUi.title, taskUi.title) },
            onDismiss = onDismiss,
        )
    }
    val menuEntries = remember(taskUi) {
        buildTaskContextMenu(
            taskUi = taskUi,
            // domainTask removed — any visible task with a title is AI-context eligible
            hasAiContext = taskUi.title.isNotBlank(),
            actions = menuActions,
        )
    }
    ContextMenuHost(
        openState = ContextMenuOpenState(offset),
        onDismiss = onDismiss,
        entries = menuEntries,
    )
}
