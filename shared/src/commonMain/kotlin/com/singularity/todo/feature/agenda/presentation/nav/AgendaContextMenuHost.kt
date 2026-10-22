package com.singularity.todo.feature.agenda.presentation.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpOffset
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.tasks.presentation.model.TaskUi

/**
 * The agenda's task context menu, as the platform implements it.
 *
 * The menu is a platform gesture, not a platform *decision*: Android opens a
 * bottom sheet on a long press, Desktop opens a popup on a right click, and both
 * dispatch the same [AgendaIntent]s. The graph therefore takes the host as a
 * parameter, and what lives in common code is the list of sections that show it.
 *
 * @param task the task the menu acts on.
 * @param offset where the menu opens — the tap position on Desktop, unused on
 *   Android, where the sheet opens from the bottom.
 * @param onDismiss called when the menu closes.
 * @param onIntent called with the intent the chosen action stands for.
 */
typealias AgendaContextMenuHost = @Composable (
    task: TaskUi,
    offset: DpOffset,
    onDismiss: () -> Unit,
    onIntent: (AgendaIntent) -> Unit,
) -> Unit
