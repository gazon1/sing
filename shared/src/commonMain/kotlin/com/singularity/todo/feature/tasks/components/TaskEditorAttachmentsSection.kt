package com.singularity.todo.feature.tasks.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.PendingAttachment

/**
 * Attachments section — add button + pending attachment list with remove action.
 */
@Composable
fun TaskEditorAttachmentsSection(
    attachments: List<PendingAttachment>,
    onAddClick: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "Attachments",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(TestTags.TASK_EDITOR_ATTACHMENTS),
        )

        // Add attachment row
        TaskEditorAttributeRow(
            icon = Icons.Filled.AttachFile,
            label = "Add attachment",
            value = if (attachments.isNotEmpty()) "${attachments.size} attachment(s)" else null,
            onClick = onAddClick,
            modifier = Modifier.testTag(TestTags.TASK_EDITOR_ADD_ATTACHMENT),
        )

        // Pending attachments
        attachments.forEach { att ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = att.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemoveAttachment(att.path) }) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = "Remove attachment",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
