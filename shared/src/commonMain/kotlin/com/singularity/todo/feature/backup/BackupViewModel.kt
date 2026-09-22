package com.singularity.todo.feature.backup

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.auth.AuthDomain
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.backup.DefaultBackupFileNamer
import com.singularity.todo.core.backup.BackupMetadata
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.exportOptions
import com.singularity.todo.core.backup.importOptions
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
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
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {

    init {
        addCloseable(scope)
    }

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BackupUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<BackupUiEvent> = _events.asSharedFlow()

    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()

    private val effectiveUserId: UserId
        get() = AuthDomain.effectiveUserId(authRepository.currentSession.value)

    init {
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
            }
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
                    _events.emit(BackupUiEvent.Error(e.message ?: "Export failed"))
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
                    _events.emit(BackupUiEvent.Error(e.message ?: "Import failed"))
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
                    _events.emit(BackupUiEvent.Error(e.message ?: "Delete failed"))
                }
        }
    }

    fun push(backupId: com.singularity.todo.core.backup.BackupId) {
        scope.launch {
            _state.update { it.copy(isWorking = true) }
            repository.push(backupId)
                .onFailure { e ->
                    _state.update { it.copy(isWorking = false) }
                    _events.emit(BackupUiEvent.Error(e.message ?: "Push failed"))
                }
                .onSuccess {
                    _state.update { it.copy(isWorking = false) }
                    _snackbar.emit("Backup pushed")
                }
        }
    }
}
