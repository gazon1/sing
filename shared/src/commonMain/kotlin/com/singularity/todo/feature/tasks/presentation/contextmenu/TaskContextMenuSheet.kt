package com.singularity.todo.feature.tasks.presentation.contextmenu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.presentation.model.TaskUi

/**
 * Android long-press context menu for a task row.
 *
 * The desktop right-click menu is [buildTaskContextMenu] (the 28-item TickTick
 * reference) rendered by the JVM popup host; most of those items have no write
 * behind them yet. A long-press sheet reproducing all 28 would surface
 * fifteen dead rows on the platform with the least screen space for them, so
 * the touch surface lists exactly the actions that are wired, dispatching the
 * same domain intents the desktop menu dispatches. When more builder items gain
 * writes, add them here in the same order as the builder.
 *
 * Rows carry `sheet_item_<label>` tags (see [TestTags.sheetItem]) so UI
 * automation can address actions without reading visible text.
 *
 * @param task        the task the menu is open for; label text follows its state
 * @param onOpen      open the task detail
 * @param onToggleComplete toggle completion
 * @param onTogglePin toggle the pinned flag
 * @param onArchive   soft-delete — moves the task to the Archive screen, from
 *                    which its detail screen offers Восстановить
 * @param onDismiss   close the sheet; every action closes it after firing
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskContextMenuSheet(
    task: TaskUi,
    onOpen: () -> Unit,
    onToggleComplete: () -> Unit,
    onTogglePin: () -> Unit,
    onArchive: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(TestTags.TASK_CONTEXT_MENU_SHEET),
    ) {
        Column {
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            HorizontalDivider()

            SheetRow(label = "Open", onClick = onOpen, onDismiss = onDismiss)
            SheetRow(
                label = if (task.isCompleted) "Mark as uncompleted" else "Mark as completed",
                onClick = onToggleComplete,
                onDismiss = onDismiss,
            )
            SheetRow(
                label = if (task.isPinned) "Unpin" else "Pin",
                onClick = onTogglePin,
                onDismiss = onDismiss,
            )

            HorizontalDivider()

            SheetRow(label = "Archive", onClick = onArchive, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun SheetRow(
    label: String,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.sheetItem(label))
            .clickable {
                onClick()
                onDismiss()
            },
    )
}
