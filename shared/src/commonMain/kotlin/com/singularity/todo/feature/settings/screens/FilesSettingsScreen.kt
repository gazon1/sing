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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.settings.EphemeralState
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.preview.PreviewThemed

/**
 * Files settings — shows storage location, attachment management, and log export.
 *
 * @param attachmentsPath absolute path to the attachments folder (used to reveal it in file manager).
 * @param onOpenAttachmentsFolder called when the user taps "Attachments location" — should open the file manager.
 * @param logExportEphemeral current log export state (loading / result / error).
 * @param onExportLogs called when the user taps "Export Logs".
 */
@Composable
fun FilesSettingsScreen(
    attachmentsPath: String,
    onOpenAttachmentsFolder: () -> Unit,
    logExportEphemeral: EphemeralState.LogExport = EphemeralState.LogExport(),
    onExportLogs: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StorageSection(attachmentsPath, onOpenAttachmentsFolder)
        LogsSection(logExportEphemeral, onExportLogs)
        AttachmentLimitsSection()
        SupportedFormatsSection()
    }
}

@Composable
private fun StorageSection(attachmentsPath: String, onOpenAttachmentsFolder: () -> Unit) {
    SettingsSection(title = "Storage") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenAttachmentsFolder)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
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
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "Open folder",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun LogsSection(logExportEphemeral: EphemeralState.LogExport, onExportLogs: () -> Unit) {
    SettingsSection(title = "Logs") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !logExportEphemeral.isExporting, onClick = onExportLogs)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Export Logs",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = logExportSubtitle(logExportEphemeral),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (logExportEphemeral.isExporting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(start = 8.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

private fun logExportSubtitle(state: EphemeralState.LogExport): String = when {
    state.isExporting -> "Exporting…"
    state.exportedPath != null -> "Done — tap to share again"
    state.errorMessage != null -> "Error: ${state.errorMessage}"
    else -> "Create a ZIP bundle of app logs"
}

@Composable
private fun AttachmentLimitsSection() {
    SettingsSection(title = "Attachment Limits") {
        Text(
            text = "Attachments are linked to tasks and notes and are stored locally on your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SupportedFormatsSection() {
    SettingsSection(title = "Supported Formats") {
        Text(
            text = "Images: JPEG, PNG, GIF, WebP\nDocuments: PDF, DOC, DOCX, TXT\nArchives: ZIP",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
