package com.singularity.todo.feature.tasks.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.settings.ReminderOffset

/**
 * Reminder summary row — shows the selected offset label or "No reminder".
 * Tapping opens the reminder picker sheet.
 */
@Composable
fun TaskEditorReminderSection(
    reminderOffset: ReminderOffset?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TaskEditorAttributeRow(
        icon = Icons.Filled.AccessTime,
        label = "No reminder",
        value = reminderOffset?.label,
        onClick = onClick,
        modifier = modifier.testTag(TestTags.TASK_EDITOR_REMINDER),
    )
}
