package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.feature.attachments.AttachmentTile
import com.singularity.todo.feature.checklist.ChecklistItem
import com.singularity.todo.feature.checklist.ChecklistItemId
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec

// ─── Recurrence ────────────────────────────────────────────────────────────────

@Composable
fun TaskDetailRecurrenceSection(recurrence: RecurrenceSpec, modifier: Modifier = Modifier) {
    ExtraSectionCard(
        icon = { Icon(Icons.Filled.Repeat, contentDescription = null) },
        label = "Repeats",
        content = {
            Text(
                text = recurrence.toString(),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        modifier = modifier,
    )
}

// ─── Checklist ────────────────────────────────────────────────────────────────

@Suppress("FunctionSignature")
@Composable
fun TaskDetailChecklistSection(
    checklist: List<ChecklistItem>,
    onToggle: (ChecklistItem) -> Unit,
    onDelete: (ChecklistItemId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (checklist.isEmpty()) return
    ExtraSectionCard(
        icon = { Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null) },
        label = "Checklist (${checklist.count { it.isCompleted }}/${checklist.size})",
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                checklist.take(10).forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(item) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (item.isCompleted) "✓ ${item.title}" else item.title,
                            style = MaterialTheme.typography.bodySmall,
                            textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
                            color = if (item.isCompleted) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onDelete(item.id) },
                            modifier = Modifier.height(24.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Delete item",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                if (checklist.size > 10) {
                    Text(
                        "+${checklist.size - 10} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        modifier = modifier,
    )
}

// ─── Attachments ─────────────────────────────────────────────────────────────

/**
 * The task's attachments, listed in full.
 *
 * This used to `take(5)` and say nothing: a task with seven attachments showed five,
 * with no "+2 more", no scroll and no way to reach the rest. Silently dropping two
 * rows is a different failure from showing a truncated list, so the cap is gone.
 *
 * The rows are [AttachmentTile] rather than bare `Text`. The tile was implemented,
 * previewed and called by nothing outside its own file — it renders the thumbnail,
 * the file size and a type-correct icon, none of which the text row did.
 *
 * @param onAttachFile opens the platform file picker. `null` hides the button — there is
 *   no task to attach to while the task is still being created.
 * @param onOpen navigates to the viewer for one attachment. `null` renders rows that
 *   cannot be tapped, so no row advertises an interaction it does not have.
 */
@Composable
fun TaskDetailAttachmentsSection(
    attachments: List<Attachment>,
    onAttachFile: (() -> Unit)? = null,
    onDelete: ((AttachmentId) -> Unit)? = null,
    onOpen: ((AttachmentId) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty() && onAttachFile == null) return
    ExtraSectionCard(
        icon = { Icon(Icons.Filled.AttachFile, contentDescription = null) },
        label = "Attachments (${attachments.size})",
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                attachments.forEach { att ->
                    AttachmentTile(
                        attachment = att,
                        onDelete = { onDelete?.invoke(att.id) },
                        // A URL attachment has no file behind it, so it gets no open
                        // affordance rather than a row that goes nowhere.
                        onOpen = onOpen?.takeIf { !att.isUrl }?.let { open -> { open(att.id) } },
                    )
                }
                if (onAttachFile != null) {
                    Text(
                        text = "+ Add file",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clickable(onClick = onAttachFile),
                    )
                }
            }
        },
        modifier = modifier,
    )
}
