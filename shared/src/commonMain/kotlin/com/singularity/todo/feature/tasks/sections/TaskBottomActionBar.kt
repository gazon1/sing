package com.singularity.todo.feature.tasks.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Badge
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tasks.TaskDetailActions

/**
 * The bottom action bar of a task detail screen — pinned notifications,
 * attachments, pin, and delete buttons.
 *
 * @param remindersCount   Number of active reminders — shown as a badge on the bell icon.
 * @param attachmentsCount Number of attachments — shown as a badge on the attach icon.
 * @param isPinned        Whether the task is pinned.
 * @param actions         Packed [TaskDetailActions] callback handler.
 */
@Composable
fun TaskBottomActionBar(
    remindersCount: Int,
    attachmentsCount: Int,
    isPinned: Boolean,
    actions: TaskDetailActions,
    modifier: Modifier = Modifier,
) {
    BottomAppBar(
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Remind
            IconButtonWithBadge(
                icon = Icons.Filled.Notifications,
                badgeCount = remindersCount,
                contentDescription = "Remind",
                onClick = actions::onRemind,
            )
            // Attach
            IconButtonWithBadge(
                icon = Icons.Filled.AttachFile,
                badgeCount = attachmentsCount,
                contentDescription = "Attach",
                onClick = actions::onAttach,
            )
            // Pin
            IconButton(onClick = actions::onPin) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = if (isPinned) "Unpin" else "Pin",
                    tint = if (isPinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            // Delete
            IconButton(onClick = actions::onDelete) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * An [IconButton] with a [Badge] overlaid in the top-end corner.
 * The badge is only visible when [badgeCount] > 0.
 */
@Composable
private fun IconButtonWithBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    badgeCount: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription)
        }
        if (badgeCount > 0) {
            Badge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp),
            ) {
                Text("$badgeCount")
            }
        }
    }
}
