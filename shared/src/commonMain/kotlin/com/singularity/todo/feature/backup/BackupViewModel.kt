package com.singularity.todo.feature.backup

import com.singularity.todo.core.auth.AuthDomain
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.backup.BackupId
import com.singularity.todo.core.backup.BackupMetadata
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.DefaultBackupFileNamer
import com.singularity.todo.core.backup.exportOptions
import com.singularity.todo.core.backup.importOptions
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.settings.SettingsExporter
import com.singularity.todo.core.settings.SettingsImporter
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.backup.BackupUiEvent.Error
import com.singularity.todo.feature.backup.BackupUiEvent.ShowSnackbar
import kotlinx.coroutines.launch
import kotlin.time.Clock

data class BackupUiState(
    val isWorking: Boolean = false,
    val backups: List<BackupMetadata> = emptyList(),
    val lastBackup: BackupSummary? = null,
)

data class BackupSummary(val destPath: String, val byteSize: Long, val entityCount: Int)

/**
 * Backup management screen ViewModel.
 *
 * Owns: local backup list, export/import/push operations.
 * Triggers: [BackupIntent] — export, import, delete, push to remote, settings snapshots.
 * One-shot events: [BackupUiEvent.ShowSnackbar], [BackupUiEvent.Error],
 * [BackupUiEvent.SettingsSnapshotExported].
 *
 * Extends [MviViewModel] so the state stream, the event channel and the coroutine
 * scope are all owned (and closed) by the base class.
 *
 * @see BackupUiState
 */
class BackupViewModel(
    private val repository: BackupRepository,
    private val authRepository: AuthRepository,
    private val backupFileNamer: DefaultBackupFileNamer,
    private val clock: Clock,
    private val settingsExporter: SettingsExporter,
    private val settingsImporter: SettingsImporter,
    scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<BackupUiState, BackupIntent, BackupUiEvent>(
        initialState = BackupUiState(),
        scope = scope,
    ) {

    private val effectiveUserId: UserId
        get() = AuthDomain.effectiveUserId(authRepository.currentSession.value)

    init {
        vmScope.launch {
            repository.observeAll().collect { backups ->
                updateState { it.copy(backups = backups) }
            }
        }
    }

    override fun onIntent(intent: BackupIntent) {
        when (intent) {
            is BackupIntent.Export -> export(intent.destPath)
            BackupIntent.CreateBackup -> export(backupFileNamer.nextBackupName(clock.now().toEpochMilliseconds()))
            is BackupIntent.Import -> import(intent.sourcePath)
            BackupIntent.ExportSettingsSnapshot -> exportSettingsSnapshot()
            is BackupIntent.ImportSettingsSnapshot -> importSettingsSnapshot(intent.json)
            is BackupIntent.Delete -> delete(intent.backupId)
            is BackupIntent.Push -> push(intent.backupId)
        }
    }

    private fun export(destPath: String) {
        vmScope.launch {
            updateState { it.copy(isWorking = true) }
            val result = repository.export(
                exportOptions {
                    userId = effectiveUserId
                    this.destPath = destPath
                    includeAttachments = true
                },
            )
            result
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
                    emit(ShowSnackbar("Backup created"))
                }
                .onFailure { e ->
                    updateState { it.copy(isWorking = false) }
                    emit(Error(e.message ?: "Export failed"))
                }
        }
    }

    private fun import(sourcePath: String) {
        vmScope.launch {
            updateState { it.copy(isWorking = true) }
            val opts = importOptions {
                this.sourcePath = sourcePath
                this.targetUserId = effectiveUserId
            }
            val result = repository.import(opts)
            result
                .onSuccess {
                    updateState { it.copy(isWorking = false) }
                    emit(ShowSnackbar("Restore complete"))
                }
                .onFailure { e ->
                    updateState { it.copy(isWorking = false) }
                    emit(Error(e.message ?: "Import failed"))
                }
        }
    }

    /**
     * Exports current settings as a JSON snapshot and emits [BackupUiEvent.SettingsSnapshotExported].
     * The shell should present the JSON to the user via system share sheet.
     *
     * No `ShowSnackbar` is emitted alongside it: the screen already renders a snackbar
     * for [BackupUiEvent.SettingsSnapshotExported] (with a "Share" action), and the two
     * emissions used to stack into the same message appearing twice.
     */
    private fun exportSettingsSnapshot() {
        vmScope.launch {
            updateState { it.copy(isWorking = true) }
            runCatching {
                settingsExporter.exportAsJson()
            }.onSuccess { json ->
                updateState { it.copy(isWorking = false) }
                emit(BackupUiEvent.SettingsSnapshotExported(json))
            }.onFailure { e ->
                updateState { it.copy(isWorking = false) }
                emit(Error(e.message ?: "Settings export failed"))
            }
        }
    }

    /**
     * Imports settings from a JSON snapshot string.
     * The JSON may come from a file the user selected via platform file picker.
     */
    private fun importSettingsSnapshot(json: String) {
        vmScope.launch {
            updateState { it.copy(isWorking = true) }
            when (val result = settingsImporter.importFromJson(json)) {
                is SettingsImporter.ImportResult.Success -> {
                    updateState { it.copy(isWorking = false) }
                    emit(ShowSnackbar("Settings restored"))
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
    }

    private fun delete(backupId: BackupId) {
        vmScope.launch {
            repository.delete(backupId)
                .onSuccess { emit(ShowSnackbar("Backup deleted")) }
                .onFailure { e -> emit(Error(e.message ?: "Delete failed")) }
        }
    }

    private fun push(backupId: BackupId) {
        vmScope.launch {
            updateState { it.copy(isWorking = true) }
            repository.push(backupId)
                .onFailure { e ->
                    updateState { it.copy(isWorking = false) }
                    emit(Error(e.message ?: "Push failed"))
                }
                .onSuccess {
                    updateState { it.copy(isWorking = false) }
                    emit(ShowSnackbar("Backup pushed"))
                }
        }
    }
}
