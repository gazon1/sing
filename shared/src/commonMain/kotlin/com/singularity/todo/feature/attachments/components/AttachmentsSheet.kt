package com.singularity.todo.feature.attachments.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.core.attachments.AttachmentId
import com.singularity.todo.core.attachments.AttachmentType
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.preview.noopClick
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.components.TaskEditorSheetHost

/**
 * The attachments sheet: add a link, add a file, remove an attachment.
 *
 * @param onAttachFile opens the platform file picker. `null` hides the "Add file"
 *   button — there is no file to attach before the task exists, and a button that
 *   opens nothing is worse than no button. It was previously a non-null `() -> Unit`
 *   that Create mode passed as `{}`, and which this sheet's own body never called, so
 *   the branch was dead in two places at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentsSheet(
    taskId: TaskId?,
    attachments: List<Attachment>,
    onAddUrl: (String, String?) -> Unit,
    onAttachFile: (() -> Unit)? = null,
    onDelete: (AttachmentId) -> Unit,
    onDismiss: () -> Unit,
) {
    val urlState = rememberTextFieldState()
    val titleState = rememberTextFieldState()

    TaskEditorSheetHost(
        title = "Attachments",
        onClose = onDismiss,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Add URL
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    state = urlState,
                    modifier = Modifier.weight(1f).testTag(TestTags.ATTACHMENTS_URL_INPUT),
                    placeholder = { Text("URL") },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    state = titleState,
                    modifier = Modifier.weight(0.6f).testTag(TestTags.ATTACHMENTS_TITLE_INPUT),
                    placeholder = { Text("Title (opt)") },
                    lineLimits = TextFieldLineLimits.SingleLine,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onAttachFile != null) {
                    OutlinedButton(
                        onClick = onAttachFile,
                        modifier = Modifier.testTag(TestTags.ATTACHMENTS_ADD_FILE_ROW),
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add file")
                    }
                }
                FilledTonalButton(
                    onClick = {
                        val url = urlState.text.toString()
                        if (url.isNotBlank()) {
                            onAddUrl(url.trim(), titleState.text.toString().ifBlank { null })
                            urlState.clearText()
                            titleState.clearText()
                        }
                    },
                    enabled = urlState.text.isNotBlank(),
                    modifier = Modifier.testTag(TestTags.ATTACHMENTS_ADD_URL_BUTTON),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add URL")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (attachments.isEmpty()) {
                Text(
                    text = "No attachments yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(attachments, key = { it.id.value }) { attachment ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Link,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = attachment.title ?: attachment.url ?: "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (attachment.title != null) {
                                        Text(
                                            text = attachment.url ?: "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                IconButton(onClick = { onDelete(attachment.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AttachmentsSheetEmptyPreview() = PreviewThemed(darkTheme = false) {
    AttachmentsSheet(
        taskId = TaskId("t1"),
        attachments = emptyList(),
        onAddUrl = { _, _ -> },
        onAttachFile = noopClick,
        onDelete = { },
        onDismiss = noopClick,
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AttachmentsSheetWithItemsPreview() = PreviewThemed(darkTheme = false) {
    AttachmentsSheet(
        taskId = TaskId("t1"),
        attachments = listOf(
            Attachment(
                id = AttachmentId.fromString("att_1"),
                taskId = TaskId("t1"),
                userId = UserId.anonymous,
                type = AttachmentType.Url,
                url = "https://example.com/spec",
                title = "Project spec",
                createdAt = PreviewSamples.now,
                updatedAt = PreviewSamples.now,
            ),
            Attachment(
                id = AttachmentId.fromString("att_2"),
                taskId = TaskId("t1"),
                userId = UserId.anonymous,
                type = AttachmentType.File,
                title = "report.pdf",
                fileSizeBytes = 12_345L,
                mimeType = "application/pdf",
                createdAt = PreviewSamples.now,
                updatedAt = PreviewSamples.now,
            ),
        ),
        onAddUrl = { _, _ -> },
        onAttachFile = noopClick,
        onDelete = { },
        onDismiss = noopClick,
    )
}
