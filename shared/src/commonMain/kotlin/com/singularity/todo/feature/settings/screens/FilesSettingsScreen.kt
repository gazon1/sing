package com.singularity.todo.feature.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.preview.PreviewThemed

/**
 * Files settings — shows storage location and attachment management info.
 * Attachments are stored under the app's internal files directory.
 *
 * @param attachmentsPath absolute path to the attachments folder (used to reveal it in file manager).
 * @param onOpenAttachmentsFolder called when the user taps "Attachments location" — should open the file manager.
 */
@Composable
fun FilesSettingsScreen(
    attachmentsPath: String,
    onOpenAttachmentsFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Storage") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAttachmentsFolder)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Attachments location",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = attachmentsPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = "Open folder",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        SettingsSection(title = "Attachment Limits") {
            Text(
                text = "Attachments are linked to tasks and notes and are stored locally on your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsSection(title = "Supported Formats") {
            Text(
                text = "Images: JPEG, PNG, GIF, WebP\nDocuments: PDF, DOC, DOCX, TXT\nArchives: ZIP",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun FilesSettingsScreenLightPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    FilesSettingsScreen(
        attachmentsPath = "/data/user/0/com.singularity.todo/files/attachments",
        onOpenAttachmentsFolder = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun FilesSettingsScreenDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    FilesSettingsScreen(
        attachmentsPath = "/data/user/0/com.singularity.todo/files/attachments",
        onOpenAttachmentsFolder = {},
    )
}
