package com.singularity.todo.feature.attachments

import com.singularity.todo.core.ui.preview.PreviewSamples
import com.singularity.todo.core.ui.preview.PreviewThemed
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.attachments.Attachment
import com.singularity.todo.core.attachments.AttachmentDomain

@Composable
fun AttachmentTile(
    attachment: Attachment,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon/thumbnail
        when {
            attachment.isImage -> AttachmentThumbnail(
                localPath = attachment.localPath,
                remoteUrl = attachment.remoteUrl,
                modifier = Modifier.size(40.dp)
            )
            else -> Icon(
                imageVector = when {
                    attachment.isUrl -> Icons.Default.Link
                    else -> Icons.Default.Description
                },
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title + size
        Text(
            text = attachment.displayTitle,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // File size (for file attachments)
        if (attachment.fileSizeBytes > 0) {
            Text(
                text = AttachmentDomain.formatFileSize(attachment.fileSizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        // Delete
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete attachment",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AttachmentTileFileLightPreview() = PreviewThemed(darkTheme = false) {
    AttachmentTile(
        attachment = PreviewSamples.attachment(
            type = com.singularity.todo.core.attachments.AttachmentType.File,
            title = "quarterly-report.pdf",
        ),
        onDelete = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AttachmentTileUrlDarkPreview() = PreviewThemed(darkTheme = true) {
    AttachmentTile(
        attachment = PreviewSamples.attachment(
            type = com.singularity.todo.core.attachments.AttachmentType.Url,
            title = "https://example.com/article",
        ),
        onDelete = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun AttachmentTileImagePurpleDarkPreview() = PreviewThemed(
    darkTheme = true,
    accent = com.singularity.todo.core.ui.theme.SingularityAccents.Purple,
) {
    AttachmentTile(
        attachment = PreviewSamples.attachment(
            type = com.singularity.todo.core.attachments.AttachmentType.Image,
            title = "screenshot.png",
        ),
        onDelete = {},
    )
}
