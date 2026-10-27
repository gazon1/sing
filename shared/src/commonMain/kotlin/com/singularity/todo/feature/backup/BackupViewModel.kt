package com.singularity.todo.feature.backup

import com.singularity.todo.core.auth.AuthDomain
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.backup.BackupMetadata
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.DefaultBackupFileNamer
import com.singularity.todo.core.backup.RemoteBackup
import com.singularity.todo.core.backup.exportOptions
import com.singularity.todo.core.backup.importOptions
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.files.FileSharePort
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
    /**
     * The archives that live in the cloud, once they have been asked for.
     *
     * Empty means "not looked yet", not "there are none": the two are different
     * answers, and a screen that cannot tell them shows an empty list where the
     * answer is "the cloud was never asked".
     */
    val remoteBackups: List<RemoteBackup> = emptyList(),
    /** Whether the cloud has been asked for its archives at least once. */
    val remoteBackupsLoaded: Boolean = false,
    /**
     * Whether this device may seal archives on its own.
     *
     * False means the daily backup stays on the device, and the person is asked
     * for a password every time they want one in the cloud. True means the
     * device holds a key and the daily run can push without anyone there.
     */
    val scheduledBackupAuthorized: Boolean = false,
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
    private val fileSharePort: FileSharePort,
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
        scope.launch {
            // Whether this device may seal archives on its own is a fact about the
            // device, and the screen says so before the person presses anything.
            val authorized = repository.isScheduledBackupAuthorized()
            updateState { it.copy(scheduledBackupAuthorized = authorized) }
        }
    }

    override fun onIntent(intent: BackupIntent) {
        when (intent) {
            BackupIntent.CreateBackup -> vmScope.launch { runCreateBackup() }
            is BackupIntent.Restore -> vmScope.launch { runRestore(intent.sourcePath) }
            is BackupIntent.Delete -> vmScope.launch { runDelete(intent.id) }
            is BackupIntent.Push -> vmScope.launch { runPush(intent.id, intent.password) }
            BackupIntent.LoadRemoteBackups -> vmScope.launch { runLoadRemoteBackups() }
            is BackupIntent.RestoreFromCloud -> vmScope.launch { runRestoreFromCloud(intent) }
            is BackupIntent.AuthorizeScheduledBackup -> vmScope.launch { runAuthorizeScheduledBackup(intent) }
            is BackupIntent.SaveToFile -> vmScope.launch { runSaveToFile(intent.id) }
            BackupIntent.ExportSettingsSnapshot -> vmScope.launch { runExportSettingsSnapshot() }
            is BackupIntent.ImportSettingsSnapshot -> vmScope.launch { runImportSettingsSnapshot(intent.json) }
            is BackupIntent.ImportSettingsFrom -> vmScope.launch { runImportSettingsFrom(intent.sourcePath) }
        }
    }

    private suspend fun runCreateBackup() {
        // A file name, not a path: the repository owns the backup directory and
        // joins the two. Passing a path here would aim the archive outside that
        // directory — on Android that is the read-only root.
        val fileName = backupFileNamer.nextBackupName(clock.now().toEpochMilliseconds())
        updateState { it.copy(isWorking = true) }
        repository.export(
            exportOptions {
                userId = effectiveUserId
                this.destPath = fileName
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
                val failure = failure(e, BACKUP_EXPORT_FAILED, "Export failed")
                crashReporter.report(e, failure.code)
                emit(Error(failure.message))
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
                val failure = failure(e, BACKUP_RESTORE_FAILED, "Import failed")
                crashReporter.report(e, failure.code)
                emit(Error(failure.message))
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
                val failure = failure(e, BACKUP_DELETE_FAILED, "Delete failed")
                crashReporter.report(e, failure.code)
                emit(Error(failure.message))
            }
    }

    /**
     * Uploads the archive, sealed under the person's password.
     *
     * The password is asked for at the moment of the push and is not kept: not in
     * the state, not in the settings, not in the keychain. It travels to the
     * repository and is wiped there, whatever the upload answered.
     */
    private suspend fun runPush(
        backupId: com.singularity.todo.core.backup.BackupId,
        password: CharArray,
    ) {
        updateState { it.copy(isWorking = true) }
        repository.push(backupId, password)
            .onFailure { e ->
                updateState { it.copy(isWorking = false) }
                // The error's own code when it has one: a refused session, a
                // bucket that does not exist and a lost connection are three
                // defects, and one key for all three is how "the anon key went
                // instead of the user's token" and "the row-level policy
                // refused the write" ended up in the same group.
                val failure = failure(e, BACKUP_PUSH_FAILED, "Push failed")
                crashReporter.report(e, failure.code)
                emit(Error(failure.message))
            }
            .onSuccess {
                updateState { it.copy(isWorking = false) }
                tryEmit(BackupUiEvent.ShowSnackbar("Backup pushed"))
            }
    }

    /**
     * Reads the archives that live in the cloud.
     *
     * The other half of the push: without it, an archive that was uploaded can never
     * be fetched, and the person who uploaded it has no way to know that.
     */
    private suspend fun runLoadRemoteBackups() {
        updateState { it.copy(isWorking = true) }
        repository.listRemote()
            .onFailure { e ->
                updateState { it.copy(isWorking = false) }
                val failure = failure(e, BACKUP_LIST_FAILED, "Could not read the cloud backups")
                crashReporter.report(e, failure.code)
                emit(Error(failure.message))
            }
            .onSuccess { backups ->
                updateState {
                    it.copy(
                        isWorking = false,
                        remoteBackups = backups,
                        remoteBackupsLoaded = true,
                    )
                }
            }
    }

    /**
     * Restores an archive that lives in the cloud.
     *
     * The download and the import are one step: the archive lands in the app's own
     * backup directory and is imported from there, so a person who pressed the button
     * either gets their data back or gets an answer.
     *
     * The archive is sealed in the bucket and is opened here, under the password the
     * person typed. A wrong password is the answer the app gives — not a broken
     * archive and not a corrupt download.
     */
    private suspend fun runRestoreFromCloud(intent: BackupIntent.RestoreFromCloud) {
        updateState { it.copy(isWorking = true) }
        repository.restoreFromCloud(intent.remoteRef, intent.password)
            .onFailure { e ->
                updateState { it.copy(isWorking = false) }
                val failure = failure(e, BACKUP_RESTORE_FAILED, "Restore from the cloud failed")
                crashReporter.report(e, failure.code)
                emit(Error(failure.message))
            }
            .onSuccess {
                updateState { it.copy(isWorking = false) }
                tryEmit(BackupUiEvent.ShowSnackbar("Restore complete"))
            }
    }

    /**
     * Lets this device seal archives on its own.
     *
     * The password is asked once, and it does not stay: it wraps the device key,
     * and the wrapped key is what travels from then on. The person who restores
     * an archive still needs their password — this only removes the need for one
     * to be *there* at push time.
     */
    private suspend fun runAuthorizeScheduledBackup(intent: BackupIntent.AuthorizeScheduledBackup) {
        updateState { it.copy(isWorking = true) }
        repository.authorizeScheduledBackup(intent.password)
            .onSuccess {
                updateState { it.copy(isWorking = false, scheduledBackupAuthorized = true) }
                tryEmit(BackupUiEvent.ShowSnackbar("This device can now back up on its own"))
            }
            .onFailure { e ->
                updateState { it.copy(isWorking = false) }
                val failure = failure(e, BACKUP_AUTHORIZE_FAILED, "Could not authorize this device")
                crashReporter.report(e, failure.code)
                emit(Error(failure.message))
            }
    }

    /**
     * Hands the archive to the platform's share sheet.
     *
     * The file stays where it is — the share sheet copies it wherever the user
     * chooses — so this is a copy-out, not a move. A user who shares a backup to
     * "Files" and then deletes it from the app keeps the copy they made.
     */
    private suspend fun runSaveToFile(backupId: com.singularity.todo.core.backup.BackupId) {
        val backup = currentState.backups.find { it.id == backupId }
        if (backup == null) {
            emit(Error("That backup is no longer there"))
            return
        }
        val shared = fileSharePort.shareFile(backup.path, BACKUP_MIME_TYPE)
        if (shared) {
            tryEmit(BackupUiEvent.ShowSnackbar("Backup handed over"))
        } else {
            emit(Error("No app on this device can take the backup"))
        }
    }

    /**
     * The failure [error] means, in the words the screen shows and the report
     * groups by.
     *
     * The backup layer reports three families of failure, and each case in each
     * family is something a person can act on. The mapping lives in one place —
     * [backupFailure] — so the screen and the report cannot drift apart.
     */
    private fun failure(error: Throwable, fallbackCode: String, fallbackMessage: String): BackupFailure =
        backupFailure(error, BackupFailure(fallbackCode, fallbackMessage))

    private companion object {
        // Machine-shaped grouping keys — these leave the device. The settings-decode
        // outcomes (SchemaTooOld, ParseError) are deliberately absent: they are the user
        // handing the app a file it cannot read, which the KDoc above treats as an ordinary
        // message rather than a defect.
        const val BACKUP_EXPORT_FAILED = "backup.export_failed"
        const val BACKUP_RESTORE_FAILED = "backup.restore_failed"

        /** The MIME type a backup archive is handed over with. */
        const val BACKUP_MIME_TYPE = "application/zip"
        const val BACKUP_DELETE_FAILED = "backup.delete_failed"
        const val BACKUP_PUSH_FAILED = "backup.push_failed"
        const val BACKUP_LIST_FAILED = "backup.list_failed"
        const val BACKUP_AUTHORIZE_FAILED = "backup.authorize_failed"
        const val SETTINGS_EXPORT_FAILED = "backup.settings_export_failed"
        const val SETTINGS_FILE_READ_FAILED = "backup.settings_file_read_failed"
    }
}
