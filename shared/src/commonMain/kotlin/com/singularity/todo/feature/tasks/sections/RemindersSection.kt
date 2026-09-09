package com.singularity.todo.feature.tasks.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.formatReminderTime
import com.singularity.todo.feature.reminders.Reminder
import com.singularity.todo.feature.tasks.components.TaskDetailActions
import kotlinx.datetime.TimeZone

/**
 * The reminders section of a task detail screen — shows a list of reminder rows
 * each with a Notifications icon, formatted time, and a delete button.
 *
 * @param reminders All reminders belonging to the current task.
 * @param timeZone The user's local timezone for formatting.
 * @param actions Packed [TaskDetailActions] callback handler.
 */
@Composable
fun RemindersSection(
    reminders: List<Reminder>,
    timeZone: TimeZone,
    actions: TaskDetailActions,
    modifier: Modifier = Modifier,
) {
    if (reminders.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Reminders",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "${reminders.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        reminders.forEach { reminder ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = formatReminderTime(
                        fireAt = reminder.fireAt,
                        offsetMinutes = reminder.offsetMinutes,
                        zone = timeZone,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { actions.onDeleteReminder(reminder) },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete reminder",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}
