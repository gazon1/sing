package com.singularity.todo.feature.backup

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.auth.AuthDomain
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.backup.BackupMetadata
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.DefaultBackupFileNamer
import com.singularity.todo.core.backup.exportOptions
import com.singularity.todo.core.backup.importOptions
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.settings.SettingsImporter
import com.singularity.todo.feature.backup.BackupUiEvent.Error
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
 * Triggers: export, import, delete, push to remote, pull from remote.
 * One-shot events: [BackupUiEvent.ShowSnackbar], [BackupUiEvent.Error].
 *
 * @see BackupUiState
 */
class BackupViewModel(
    private val repository: BackupRepository,
    private val authRepository: AuthRepository,
    private val backupFileNamer: DefaultBackupFileNamer,
    private val clock: Clock,
    private val settingsExporter: com.singularity.todo.core.settings.SettingsExporter,
    private val settingsImporter: SettingsImporter,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {



    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BackupUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<BackupUiEvent> = _events.asSharedFlow()

    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()

    private val effectiveUserId: UserId
        get() = AuthDomain.effectiveUserId(authRepository.currentSession.value)

    init {
        addCloseable(scope)
        scope.launch {
            repository.observeAll().collect { backups ->
                _state.update { it.copy(backups = backups) }
            }
        }
    }

    fun export(destPath: String) {
        scope.launch {
            _state.update { it.copy(isWorking = true) }
            val result = repository.export(
                exportOptions {
                    userId = effectiveUserId
                    this.destPath = destPath
                    includeAttachments = true
                },
            )
            result
                .onSuccess { br ->
                    _state.update {
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
                    _snackbar.emit("Backup created")
                }
                .onFailure { e ->
                    _state.update { it.copy(isWorking = false) }
                    _events.emit(Error(e.message ?: "Export failed"))
                }
        }
    }

    /** Parameterless backup — uses a timestamped default path under the working directory. */
    fun createBackup() {
        val ts = clock.now().toEpochMilliseconds()
        val path = backupFileNamer.nextBackupName(ts)
        export(path)
    }

    fun import(sourcePath: String) {
        scope.launch {
            _state.update { it.copy(isWorking = true) }
            val opts = importOptions {
                this.sourcePath = sourcePath
                this.targetUserId = effectiveUserId
            }
            val result = repository.import(opts)
            result
                .onSuccess {
                    _state.update { it.copy(isWorking = false) }
                    _snackbar.emit("Restore complete")
                }
                .onFailure { e ->
                    _state.update { it.copy(isWorking = false) }
                    _events.emit(Error(e.message ?: "Import failed"))
                }
        }
    }

    /**
     * Exports current settings as a JSON snapshot and emits [BackupUiEvent.SettingsSnapshotExported].
     * The shell should present the JSON to the user via system share sheet.
     */
    fun exportSettingsSnapshot() {
        scope.launch {
            _state.update { it.copy(isWorking = true) }
            runCatching {
                settingsExporter.exportAsJson()
            }.onSuccess { json ->
                _state.update { it.copy(isWorking = false) }
                _events.emit(BackupUiEvent.SettingsSnapshotExported(json))
                _snackbar.emit("Settings snapshot ready")
            }.onFailure { e ->
                _state.update { it.copy(isWorking = false) }
                _events.emit(Error(e.message ?: "Settings export failed"))
            }
        }
    }

    /**
     * Imports settings from a JSON snapshot string.
     * The JSON may come from a file the user selected via platform file picker.
     */
    fun importSettingsSnapshot(json: String) {
        scope.launch {
            _state.update { it.copy(isWorking = true) }
            when (val result = settingsImporter.importFromJson(json)) {
                is SettingsImporter.ImportResult.Success -> {
                    _state.update { it.copy(isWorking = false) }
                    _snackbar.emit("Settings restored")
                }

                is SettingsImporter.ImportResult.SchemaTooOld -> {
                    _state.update { it.copy(isWorking = false) }
                    _events.emit(
                        Error(
                            "Settings snapshot is from an older app version (v${result.snapshotVersion}). " +
                                "Please update the app first.",
                        ),
                    )
                }

                is SettingsImporter.ImportResult.ParseError -> {
                    _state.update { it.copy(isWorking = false) }
                    _events.emit(Error("Invalid settings file: ${result.message}"))
                }

                is SettingsImporter.ImportResult.PartialFailure -> {
                    _state.update { it.copy(isWorking = false) }
                    _events.emit(Error("Some settings could not be restored: ${result.failures.joinToString("; ")}"))
                }
            }
        }
    }

    fun delete(backupId: com.singularity.todo.core.backup.BackupId) {
        scope.launch {
            repository.delete(backupId)
                .onSuccess {
                    _snackbar.emit("Backup deleted")
                }
                .onFailure { e ->
                    _events.emit(Error(e.message ?: "Delete failed"))
                }
        }
    }

    fun push(backupId: com.singularity.todo.core.backup.BackupId) {
        scope.launch {
            _state.update { it.copy(isWorking = true) }
            repository.push(backupId)
                .onFailure { e ->
                    _state.update { it.copy(isWorking = false) }
                    _events.emit(Error(e.message ?: "Push failed"))
                }
                .onSuccess {
                    _state.update { it.copy(isWorking = false) }
                    _snackbar.emit("Backup pushed")
                }
        }
    }
}
