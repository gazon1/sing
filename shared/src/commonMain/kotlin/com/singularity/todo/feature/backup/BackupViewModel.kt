package com.singularity.todo.feature.backup

import com.singularity.todo.core.auth.AuthDomain
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.backup.BackupMetadata
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.DefaultBackupFileNamer
import com.singularity.todo.core.backup.exportOptions
import com.singularity.todo.core.backup.importOptions
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.files.FileSourceFactory
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.settings.SettingsImporter
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.backup.BackupUiEvent.Error
import kotlinx.coroutines.launch
import kotlin.time.Clock
import com.singularity.todo.core.error.runCatchingCancellable

data class BackupUiState(
    val isWorking: Boolean = false,
    val backups: List<BackupMetadata> = emptyList(),
    val lastBackup: BackupSummary? = null,
)

data class BackupSummary(val destPath: String, val byteSize: Long, val entityCount: Int)

/**
 * Backup management screen ViewModel.
 *
 * Owns: local backup list, export/import/push/pull operations.
 * Triggers: [BackupIntent] — export, import, delete, push to remote, settings snapshot.
 * One-shot events: [BackupUiEvent.Error], [BackupUiEvent.SettingsSnapshotExported].
 *
 * Success snackbars are a separate stream ([snackbar]) rather than events: a snackbar is
 * a notification, not a navigation or dialog request, and the screen renders it from its
 * own collector.
 *
 * The public methods ([createBackup], [delete], [push], …) forward to [onIntent] so a
 * screen can use the operation name; [onIntent] stays the single dispatch point.
 *
 * @see BackupUiState
 * @see BackupIntent
 */
class BackupViewModel(
    private val repository: BackupRepository,
    private val authRepository: AuthRepository,
    private val backupFileNamer: DefaultBackupFileNamer,
    private val clock: Clock,
    private val settingsExporter: com.singularity.todo.core.settings.SettingsExporter,
    private val settingsImporter: SettingsImporter,
    private val fileSourceFactory: FileSourceFactory,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<BackupUiState, BackupIntent, BackupUiEvent>(
        initialState = BackupUiState(),
        crashReporter = crashReporter,
        scope = scope,
    ) {

    private val effectiveUserId: UserId
        get() = AuthDomain.effectiveUserId(authRepository.currentSession.value)

    init {
        addCloseable(scope)
        scope.launch {
            repository.observeAll().collect { backups ->
                updateState { it.copy(backups = backups) }
            }
        }
    }

    override fun onIntent(intent: BackupIntent) {
        when (intent) {
            BackupIntent.CreateBackup -> vmScope.launch { runCreateBackup() }
            is BackupIntent.Restore -> vmScope.launch { runRestore(intent.sourcePath) }
            is BackupIntent.Delete -> vmScope.launch { runDelete(intent.id) }
            is BackupIntent.Push -> vmScope.launch { runPush(intent.id) }
            BackupIntent.ExportSettingsSnapshot -> vmScope.launch { runExportSettingsSnapshot() }
            is BackupIntent.ImportSettingsSnapshot -> vmScope.launch { runImportSettingsSnapshot(intent.json) }
            is BackupIntent.ImportSettingsFrom -> vmScope.launch { runImportSettingsFrom(intent.sourcePath) }
        }
    }

    private suspend fun runCreateBackup() {
        val destPath = backupFileNamer.nextBackupName(clock.now().toEpochMilliseconds())
        updateState { it.copy(isWorking = true) }
        repository.export(
            exportOptions {
                userId = effectiveUserId
                this.destPath = destPath
                includeAttachments = true
            },
        )
            .onSuccess { br ->
                updateState {
                    it.copy(
                        isWorking = false,
                        lastBackup = BackupSummary(
                            destPath = br.destPath,
                            byteSize = br.byteSize,
                            entityCount = br.manifest.entityCounts.tasks +
                                br.manifest.entityCounts.notes +
                                br.manifest.entityCounts.projects,
                        ),
                    )
                }
                tryEmit(BackupUiEvent.ShowSnackbar("Backup created"))
            }
            .onFailure { e ->
                updateState { it.copy(isWorking = false) }
                crashReporter.report(e, BACKUP_EXPORT_FAILED)
                emit(Error(e.message ?: "Export failed"))
            }
    }

    private suspend fun runRestore(sourcePath: String) {
        updateState { it.copy(isWorking = true) }
        val opts = importOptions {
            this.sourcePath = sourcePath
            this.targetUserId = effectiveUserId
        }
        repository.import(opts)
            .onSuccess {
                updateState { it.copy(isWorking = false) }
                tryEmit(BackupUiEvent.ShowSnackbar("Restore complete"))
            }
            .onFailure { e ->
                updateState { it.copy(isWorking = false) }
                crashReporter.report(e, BACKUP_RESTORE_FAILED)
                emit(Error(e.message ?: "Import failed"))
            }
    }

    private suspend fun runExportSettingsSnapshot() {
        updateState { it.copy(isWorking = true) }
        runCatchingCancellable { settingsExporter.exportAsJson() }
            .onSuccess { json ->
                updateState { it.copy(isWorking = false) }
                emit(BackupUiEvent.SettingsSnapshotExported(json))
                tryEmit(BackupUiEvent.ShowSnackbar("Settings snapshot ready"))
            }
            .onFailure { e ->
                updateState { it.copy(isWorking = false) }
                crashReporter.report(e, SETTINGS_EXPORT_FAILED)
                emit(Error(e.message ?: "Settings export failed"))
            }
    }

    /**
     * Read the picked settings file and hand its contents to the importer.
     *
     * The read goes through [FileSourceFactory] rather than `java.io.File` because the
     * Android picker returns a SAF `content://` URI that no filesystem API can open.
     * Decode failures are reported as an ordinary error event, not a crash — a user who
     * picks the wrong file should see a message, not lose the settings screen.
     */
    private suspend fun runImportSettingsFrom(sourcePath: String) {
        updateState { it.copy(isWorking = true) }
        val json = runCatchingCancellable {
            fileSourceFactory(sourcePath).readBytes().decodeToString()
        }
        json.onSuccess { runImportSettingsSnapshot(it) }.onFailure { e ->
            updateState { it.copy(isWorking = false) }
            // A SAF read failure is a platform problem, not a user picking the wrong file,
            // so it is reported as well as shown. The decode outcomes below are not.
            crashReporter.report(e, SETTINGS_FILE_READ_FAILED)
            emit(Error(e.message ?: "Could not read the selected file"))
        }
    }

    private suspend fun runImportSettingsSnapshot(json: String) {
        updateState { it.copy(isWorking = true) }
        when (val result = settingsImporter.importFromJson(json)) {
            is SettingsImporter.ImportResult.Success -> {
                updateState { it.copy(isWorking = false) }
                tryEmit(BackupUiEvent.ShowSnackbar("Settings restored"))
            }

            is SettingsImporter.ImportResult.SchemaTooOld -> {
                updateState { it.copy(isWorking = false) }
                emit(
                    Error(
                        "Settings snapshot is from an older app version (v${result.snapshotVersion}). " +
                            "Please update the app first.",
                    ),
                )
            }

            is SettingsImporter.ImportResult.ParseError -> {
                updateState { it.copy(isWorking = false) }
                emit(Error("Invalid settings file: ${result.message}"))
            }

            is SettingsImporter.ImportResult.PartialFailure -> {
                updateState { it.copy(isWorking = false) }
                emit(Error("Some settings could not be restored: ${result.failures.joinToString("; ")}"))
            }
        }
    }

    private suspend fun runDelete(backupId: com.singularity.todo.core.backup.BackupId) {
        repository.delete(backupId)
            .onSuccess { tryEmit(BackupUiEvent.ShowSnackbar("Backup deleted")) }
            .onFailure { e ->
                crashReporter.report(e, BACKUP_DELETE_FAILED)
                emit(Error(e.message ?: "Delete failed"))
            }
    }

    private suspend fun runPush(backupId: com.singularity.todo.core.backup.BackupId) {
        updateState { it.copy(isWorking = true) }
        repository.push(backupId)
            .onFailure { e ->
                updateState { it.copy(isWorking = false) }
                crashReporter.report(e, BACKUP_PUSH_FAILED)
                emit(Error(e.message ?: "Push failed"))
            }
            .onSuccess {
                updateState { it.copy(isWorking = false) }
                tryEmit(BackupUiEvent.ShowSnackbar("Backup pushed"))
            }
    }

    private companion object {
        // Machine-shaped grouping keys — these leave the device. The settings-decode
        // outcomes (SchemaTooOld, ParseError) are deliberately absent: they are the user
        // handing the app a file it cannot read, which the KDoc above treats as an ordinary
        // message rather than a defect.
        const val BACKUP_EXPORT_FAILED = "backup.export_failed"
        const val BACKUP_RESTORE_FAILED = "backup.restore_failed"
        const val BACKUP_DELETE_FAILED = "backup.delete_failed"
        const val BACKUP_PUSH_FAILED = "backup.push_failed"
        const val SETTINGS_EXPORT_FAILED = "backup.settings_export_failed"
        const val SETTINGS_FILE_READ_FAILED = "backup.settings_file_read_failed"
    }
}
