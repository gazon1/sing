package com.singularity.todo.feature.reminders

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * List tile showing a single reminder's offset and scheduled fire time.
 */
@Composable
fun ReminderTile(reminder: Reminder, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val fireTime = remember(reminder.fireAt) {
        val instant = Instant.fromEpochMilliseconds(reminder.fireAt)
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val month = local.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
        val hour = local.hour.toString().padStart(2, '0')
        val minute = local.minute.toString().padStart(2, '0')
        "$month ${local.dayOfMonth}, $hour:$minute"
    }
    val offsetLabel = when {
        reminder.offsetMinutes == 0 -> "At due time"
        reminder.offsetMinutes > 0 -> "${reminder.offsetMinutes} min before"
        else -> "${-reminder.offsetMinutes} min after"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Notifications,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            offsetLabel,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            fireTime,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete reminder")
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ReminderTileLightPreview() = PreviewThemed(darkTheme = false) {
    ReminderTile(
        reminder = PreviewSamples.reminder(offsetMinutes = 15),
        onDelete = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun ReminderTileDarkPreview() = PreviewThemed(darkTheme = true) {
    ReminderTile(
        reminder = PreviewSamples.reminder(offsetMinutes = 60),
        onDelete = {},
    )
}
