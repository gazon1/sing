package com.singularity.todo.feature.tasks.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.feature.tasks.components.TaskDetailActions

/**
 * The attachments section of a task detail screen — shows a horizontal scrollable
 * row of attachment cards with the file icon, title, and delete action.
 *
 * @param attachments All attachments belonging to the current task.
 * @param actions Packed [TaskDetailActions] callback handler.
 */
@Composable
fun AttachmentsSection(
    attachments: List<Attachment>,
    actions: TaskDetailActions,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Attachments",
            style = MaterialTheme.typography.titleSmall,
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            items(attachments, key = { it.id.value }) { attachment ->
                AttachmentCard(
                    attachment = attachment,
                    onDelete = { actions.onDeleteAttachment(attachment) },
                    onClick = { actions.onClickAttachment(attachment) },
                )
            }
        }
    }
}

/**
 * A single attachment card showing icon, title, and delete button.
 */
@Composable
private fun AttachmentCard(
    attachment: Attachment,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .width(120.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = when {
                    attachment.isUrl -> Icons.Filled.Link
                    attachment.isImage -> Icons.Filled.Image
                    else -> Icons.Filled.Description
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = attachment.displayTitle,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete attachment",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
