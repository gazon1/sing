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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.backup.BackupId
import com.singularity.todo.core.backup.BackupMetadata
import com.singularity.todo.core.backup.RemoteBackup
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.TaggedSnackbarHost
import com.singularity.todo.core.ui.components.ButtonSpinner
import com.singularity.todo.core.ui.components.Notification
import com.singularity.todo.core.ui.components.NotificationHost
import com.singularity.todo.core.ui.formatFileSize
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.preview.noopClick
import com.singularity.todo.core.ui.text.SelectableText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
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
    /**
     * Called when the user wants to upload a backup to the cloud.
     *
     * [password] is the key the archive is sealed with. It is asked for at the
     * moment of the push, handed over once, and kept nowhere.
     */
    onPush: (BackupId, CharArray) -> Unit,
    /**
     * Called when the user wants a copy of the backup outside the app.
     *
     * The platform shell hands the archive to the system share sheet, which is
     * where "Save to Files" and every cloud-storage app live.
     */
    onSaveToFile: (BackupId) -> Unit,
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
    /** Called when the user wants to see the archives that live in the cloud. */
    onLoadRemoteBackups: () -> Unit,
    /**
     * Called when the user picks an archive in the cloud.
     *
     * [remoteRef] is the archive's path in the bucket — the repository downloads it
     * into the app's own backup directory and imports it from there.
     *
     * [password] is the key the archive was sealed with. The archive in the bucket
     * is ciphertext, and the person who pushed it is the one who can open it.
     */
    onRestoreFromCloud: (remoteRef: String, password: CharArray) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingSettingsJson by remember { mutableStateOf<String?>(null) }

    // The archive the person is about to upload, and the question that has to be
    // answered before it leaves the device: the archive is encrypted with the
    // person's password, and the server never sees it.
    var pendingPush by remember { mutableStateOf<BackupId?>(null) }

    // The archive the person is about to restore from the cloud. The import
    // overwrites the rows it finds, so an archive from last month can undo this
    // afternoon's work without ever asking — and the archive is sealed, so the
    // password is asked in the same breath.
    var pendingCloudRestore by remember { mutableStateOf<RemoteBackup?>(null) }

    // The screen owns the single collection of the event bus. The bus is a
    // channel, and a channel has one consumer: a second collector on `events`
    // takes its share of the events away from this one. That is how every
    // error used to land in a collector that dropped it — the export failed and
    // the screen said nothing. The mapped notifications travel to the host over
    // a channel of their own, so the host is the only consumer of those.
    val notificationChannel = remember { Channel<Notification>(Channel.BUFFERED) }
    val notificationFlow = remember(notificationChannel) { notificationChannel.receiveAsFlow() }

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

                is BackupUiEvent.Error ->
                    notificationChannel.send(Notification.Error(event.message))
            }
        }
    }

    NotificationHost(events = notificationFlow)

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
        // The tagged host: the snackbar's message is the translator's, and a
        // flow that waits for "Backup created" waits forever on a Russian
        // device. The tag says a snackbar was shown without saying what it
        // said.
        snackbarHost = { TaggedSnackbarHost(snackbarHostState) },
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
                            onPush = { pendingPush = backup.id },
                            onSaveToFile = { onSaveToFile(backup.id) },
                            onDelete = { onDelete(backup.id) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // The other half of the push. A backup that was uploaded and can never be
            // fetched is a feature that stops at the button: the person has no way back
            // to their own archive, and nothing on the screen says why.
            Text(
                "Cloud backups",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (!state.remoteBackupsLoaded) {
                OutlinedButton(
                    onClick = onLoadRemoteBackups,
                    modifier = Modifier.testTag(TestTags.BACKUP_LOAD_REMOTE_BUTTON),
                    enabled = !state.isWorking,
                ) {
                    Text("Load cloud backups")
                }
            } else if (state.remoteBackups.isEmpty()) {
                Text(
                    "No archives in the cloud",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.remoteBackups.forEach { remote ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                SelectableText(
                                    text = remote.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // The bucket knows when the archive was written and how
                                // big it is, and a list of bare names makes the person
                                // guess which archive they are looking at.
                                remote.updatedAtEpochMillis?.let { written ->
                                    Text(
                                        text = formatDate(written),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                remote.sizeBytes?.let { size ->
                                    Text(
                                        text = formatFileSize(size),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { pendingCloudRestore = remote },
                                modifier = Modifier.testTag(
                                    TestTags.backupRestoreFromCloud(remote.ref),
                                ),
                                enabled = !state.isWorking,
                            ) {
                                Text("Restore")
                            }
                        }
                    }
                }
            }
        }
    }

    pendingPush?.let { id ->
        BackupPasswordDialog(
            title = "Push to cloud",
            text = "The archive is encrypted with this password before it leaves the device. " +
                "The server never sees it, and nobody can open the archive without it.",
            confirmLabel = "Push",
            onConfirm = { password ->
                pendingPush = null
                onPush(id, password)
            },
            onDismiss = { pendingPush = null },
        )
    }

    pendingCloudRestore?.let { remote ->
        BackupPasswordDialog(
            title = "Restore from cloud",
            text = "Restore overwrites the current data with the archive ${remote.name}. " +
                "The archive is encrypted — enter the password it was pushed with.",
            confirmLabel = "Restore",
            onConfirm = { password ->
                pendingCloudRestore = null
                onRestoreFromCloud(remote.ref, password)
            },
            onDismiss = { pendingCloudRestore = null },
        )
    }
}

@Composable
fun BackupListItem(
    backup: BackupMetadata,
    onRestore: () -> Unit,
    onPush: () -> Unit,
    onSaveToFile: () -> Unit,
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
                SelectableText(
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
            IconButton(
                onClick = onPush,
                modifier = Modifier.testTag(TestTags.BACKUP_PUSH_BUTTON),
            ) {
                Icon(Icons.Default.CloudUpload, "Push to cloud", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onSaveToFile) {
                Icon(Icons.Default.SaveAlt, "Save to file", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showRestoreConfirm) {
        BackupConfirmDialog(
            title = "Restore backup",
            text = "Restore will overwrite current data. Are you sure?",
            confirmLabel = "Restore",
            onConfirm = {
                showRestoreConfirm = false
                onRestore()
            },
            onDismiss = { showRestoreConfirm = false },
        )
    }

    if (showDeleteConfirm) {
        BackupConfirmDialog(
            title = "Delete backup",
            text = "This backup will be permanently deleted. This action cannot be undone.",
            confirmLabel = "Delete",
            confirmColor = MaterialTheme.colorScheme.error,
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

/**
 * The "are you sure" dialog both destructive actions ask with.
 *
 * One dialog, not two: the two actions differ in wording and in the colour of the
 * button that carries them out, and nothing else. Two copies of the same dialog
 * are two places for the dismiss behaviour to drift apart.
 */
@Composable
private fun BackupConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmColor: Color = MaterialTheme.colorScheme.primary,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = confirmColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * The dialog that asks for the password the cloud archive is sealed with.
 *
 * One dialog serves both directions — the push and the restore — because the
 * question is the same one and the answer is the same secret: the archive in the
 * bucket is ciphertext, and the key is the person's password.
 *
 * ## The password is not kept
 *
 * The field is a *local* one: the text belongs to the field, and to nobody else.
 * The password is handed over as a `CharArray` and goes straight to the repository,
 * which wipes it. It is not written to the state, not to the settings and not to
 * the keychain — a forgotten password means a lost archive, and that is the honest
 * price of a key the server never has.
 *
 * What the runtime keeps is the field's own text, for as long as the field lives,
 * and the immutable copy Compose makes of it. A secret in memory cannot be made to
 * vanish on demand — it can only stop being reachable — and what this feature
 * promises is the second thing: the password is not *kept*, and the copy that
 * travels is wiped.
 */
@Composable
private fun BackupPasswordDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val passwordState = rememberTextFieldState()
    AlertDialog(
        onDismissRequest = onDismiss,
        // A dialog is its own Android window, so the app-root
        // testTagsAsResourceId never reaches it. Without this the tags on the
        // field and the button are invisible to Maestro.
        modifier = Modifier.mapTestTagsAsResourceIds(),
        title = { Text(title) },
        text = {
            Column {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedSecureTextField(
                    state = passwordState,
                    label = { Text("Password") },
                    textObfuscationMode = TextObfuscationMode.Hidden,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.BACKUP_PASSWORD_FIELD)
                        .mapTestTagsAsResourceIds(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passwordState.text.toString().toCharArray()) },
                enabled = passwordState.text.isNotBlank(),
                modifier = Modifier
                    .testTag(TestTags.BACKUP_PASSWORD_CONFIRM)
                    .mapTestTagsAsResourceIds(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
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
        snackbarHost = { TaggedSnackbarHost(SnackbarHostState()) },
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
                            onSaveToFile = noopClick,
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
