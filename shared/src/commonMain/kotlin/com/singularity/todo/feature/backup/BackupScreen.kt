package com.singularity.todo.feature.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.backup.BackupId
import com.singularity.todo.core.backup.BackupMetadata
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.ButtonSpinner
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.formatFileSize
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.preview.noopClick
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.datetime.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    state: BackupUiState,
    events: Flow<BackupUiEvent>,
    onBack: () -> Unit,
    onCreateBackup: () -> Unit,
    /** Called when user wants to restore — platform shell should open file picker and call [onRestore]. */
    onSelectRestoreFile: () -> Unit,
    onRestore: (sourcePath: String) -> Unit,
    onDelete: (BackupId) -> Unit,
    onPush: (BackupId) -> Unit,
    /** Called when user wants to export settings as a JSON snapshot for sharing. */
    onExportSettings: () -> Unit,
    /**
     * Called when user wants to import settings from a JSON file.
     * Platform shell should open a file picker and call this with the file content.
     */
    onSelectSettingsFile: () -> Unit,
    /**
     * Called after [onExportSettings] with the settings JSON.
     * Platform shell should present the JSON to the user via system share sheet.
     * (Also called automatically when user taps the "Share settings" snackbar action.)
     */
    onShareSettingsJson: (json: String) -> Unit,
    /**
     * Called when user has selected a settings JSON file from [onSelectSettingsFile].
     * [jsonContent] is the raw content of the selected JSON file.
     */
    onImportSettings: (jsonContent: String) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingSettingsJson by remember { mutableStateOf<String?>(null) }

    // Collect SettingsSnapshotExported events and show a snackbar with share action.
    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is BackupUiEvent.SettingsSnapshotExported -> {
                    pendingSettingsJson = event.json
                    val launched = snackbarHostState.showSnackbar(
                        message = "Settings snapshot ready",
                        actionLabel = "Share",
                    )
                    // If user tapped "Share", trigger share.
                    if (launched == SnackbarResult.ActionPerformed) {
                        onShareSettingsJson(event.json)
                        pendingSettingsJson = null
                    }
                }

                is BackupUiEvent.ShowSnackbar ->
                    snackbarHostState.showSnackbar(event.message)

                is BackupUiEvent.Error -> { /* handled by NotificationHost */ }
            }
        }
    }

    NotificationHost(
        events = events.filterIsInstance<BackupUiEvent.Error>(),
        mapper = { event ->
            Notification.Error(event.message)
        },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(TestTags.BACKUP_TOP_BAR_BACK)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onCreateBackup,
                    modifier = Modifier.weight(1f).testTag(TestTags.BACKUP_CREATE_BUTTON),
                    enabled = !state.isWorking,
                ) {
                    Text("Create backup")
                }
                Button(
                    onClick = onSelectRestoreFile,
                    modifier = Modifier.weight(1f).testTag(TestTags.BACKUP_RESTORE_BUTTON),
                    enabled = !state.isWorking,
                ) {
                    Text("Restore…")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Settings snapshot buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onExportSettings,
                    modifier = Modifier.weight(1f).testTag(TestTags.BACKUP_EXPORT_SETTINGS),
                    enabled = !state.isWorking,
                ) {
                    Text("Export settings")
                }
                OutlinedButton(
                    onClick = onSelectSettingsFile,
                    modifier = Modifier.weight(1f).testTag(TestTags.BACKUP_IMPORT_SETTINGS),
                    enabled = !state.isWorking,
                ) {
                    Text("Import settings")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Working indicator
            if (state.isWorking) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ButtonSpinner()
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Working…", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Last backup summary
            state.lastBackup?.let { summary ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Last backup created", style = MaterialTheme.typography.labelMedium)
                        Text(formatFileSize(summary.byteSize), style = MaterialTheme.typography.bodyMedium)
                        Text("${summary.entityCount} items", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Backup list
            Text(
                "Local backups",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (state.backups.isEmpty()) {
                Text(
                    "No backups yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.backups, key = { it.id.value }) { backup ->
                        BackupListItem(
                            backup = backup,
                            onRestore = { onRestore(backup.path) },
                            onPush = { onPush(backup.id) },
                            onDelete = { onDelete(backup.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BackupListItem(
    backup: BackupMetadata,
    onRestore: () -> Unit,
    onPush: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = backup.id.value,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatDate(backup.createdAtEpochMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatFileSize(backup.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(onClick = { showRestoreConfirm = true }) {
                Icon(Icons.Default.Restore, "Restore", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onPush) {
                Icon(Icons.Default.CloudUpload, "Push to cloud", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("Restore backup") },
            text = { Text("Restore will overwrite current data. Are you sure?") },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    onRestore()
                }) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete backup") },
            text = { Text("This backup will be permanently deleted. This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun formatDate(epochMillis: Long): String = formatBackupDate(epochMillis, TimeZone.currentSystemDefault())

// ===== Preview =====

@Composable
private fun BackupScreenContentPreview(state: BackupUiState) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore") },
                navigationIcon = {
                    IconButton(onClick = noopClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(SnackbarHostState()) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = noopClick,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isWorking,
                ) {
                    Text("Create backup")
                }
                Button(
                    onClick = noopClick,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isWorking,
                ) {
                    Text("Restore...")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (state.isWorking) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ButtonSpinner()
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Working...", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            state.lastBackup?.let { summary ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Last backup created", style = MaterialTheme.typography.labelMedium)
                        Text(formatFileSize(summary.byteSize), style = MaterialTheme.typography.bodyMedium)
                        Text("${summary.entityCount} items", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                "Local backups",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (state.backups.isEmpty()) {
                Text(
                    "No backups yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.backups, key = { it.id.value }) { backup ->
                        BackupListItem(
                            backup = backup,
                            onRestore = noopClick,
                            onPush = noopClick,
                            onDelete = noopClick,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun BackupScreenContentPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    BackupScreenContentPreview(
        state = BackupUiState(
            isWorking = false,
            backups = listOf(
                BackupMetadata(
                    id = BackupId("backup_2026-09-05_14-30-00.zip"),
                    path = "/backups/backup_2026-09-05_14-30-00.zip",
                    createdAtEpochMillis = System.currentTimeMillis() - 86400000,
                    sizeBytes = 1_234_567,
                    entityCounts = com.singularity.todo.core.backup.EntityCounts(
                        tasks = 10,
                        notes = 5,
                        projects = 3,
                    ),
                ),
                BackupMetadata(
                    id = BackupId("backup_2026-09-04_10-15-00.zip"),
                    path = "/backups/backup_2026-09-04_10-15-00.zip",
                    createdAtEpochMillis = System.currentTimeMillis() - 172800000,
                    sizeBytes = 2_345_678,
                    entityCounts = com.singularity.todo.core.backup.EntityCounts(
                        tasks = 15,
                        notes = 8,
                        projects = 4,
                    ),
                ),
            ),
            lastBackup = BackupSummary(
                destPath = "/backups/backup_2026-09-05_14-30-00.zip",
                byteSize = 1_234_567,
                entityCount = 18,
            ),
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun BackupScreenEmptyPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    BackupScreenContentPreview(
        state = BackupUiState(
            isWorking = false,
            backups = emptyList(),
            lastBackup = null,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun BackupScreenWorkingPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    BackupScreenContentPreview(
        state = BackupUiState(
            isWorking = true,
            backups = emptyList(),
            lastBackup = null,
        ),
    )
}
