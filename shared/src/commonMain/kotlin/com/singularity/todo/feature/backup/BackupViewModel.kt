package com.singularity.todo.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.auth.AuthDomain
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.backup.BackupFileNamer
import com.singularity.todo.core.backup.BackupMetadata
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.exportOptions
import com.singularity.todo.core.backup.importOptions
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackupUiState(
    val isWorking: Boolean = false,
    val backups: List<BackupMetadata> = emptyList(),
    val lastBackup: BackupSummary? = null,
    val error: String? = null,
    val showError: Boolean = false
)

data class BackupSummary(
    val destPath: String,
    val byteSize: Long,
    val entityCount: Int
)

class BackupViewModel(
    private val repository: BackupRepository,
    private val authRepository: AuthRepository,
    private val backupFileNamer: BackupFileNamer,
    private val clock: Clock,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private val effectiveUserId: UserId
        get() = AuthDomain.effectiveUserId(authRepository.session.value)

    init {
        // Unconfined makes the flow collection synchronous so state is ready before init returns
        scope.launch(Dispatchers.Unconfined) {
            repository.backups.collect { backups ->
                _state.update { it.copy(backups = backups) }
            }
        }
    }

    fun export(destPath: String) {
        scope.launch(Dispatchers.Unconfined) {
            _state.update { it.copy(isWorking = true, error = null) }
            val result = repository.export(exportOptions {
                userId = effectiveUserId
                this.destPath = destPath
                includeAttachments = true
            })
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
                                    br.manifest.entityCounts.projects
                            )
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(isWorking = false, error = e.message, showError = true) }
                }
        }
    }

    /** Parameterless backup — uses a timestamped default path under the working directory. */
    fun createBackup() {
        val ts = clock.now().toEpochMilliseconds()
        val path = backupFileNamer.nextBackupName(ts)
        export(path)
    }

    /**
     * Restore from a local backup file.
     * Currently opens a file picker in the UI to select the file.
     * TODO: wire file picker to call import(selectedPath) when file is selected.
     */
    fun restore() {
        // No-op stub: the UI file picker integration requires platform-specific
        // file picker wiring that is pending implementation.
    }

    fun import(sourcePath: String) {
        scope.launch(Dispatchers.Unconfined) {
            _state.update { it.copy(isWorking = true, error = null) }
            val opts = importOptions {
                this.sourcePath = sourcePath
                this.targetUserId = effectiveUserId
            }
            val result = repository.import(opts)
            result
                .onSuccess {
                    _state.update { it.copy(isWorking = false) }
                }
                .onFailure { e ->
                    _state.update { it.copy(isWorking = false, error = e.message, showError = true) }
                }
        }
    }

    fun delete(backupId: com.singularity.todo.core.backup.BackupId) {
        scope.launch(Dispatchers.Unconfined) {
            repository.delete(backupId)
                .onFailure { e ->
                    _state.update { it.copy(error = e.message, showError = true) }
                }
        }
    }

    fun push(backupId: com.singularity.todo.core.backup.BackupId) {
        scope.launch(Dispatchers.Unconfined) {
            _state.update { it.copy(isWorking = true) }
            repository.push(backupId)
                .onFailure { e ->
                    _state.update { it.copy(isWorking = false, error = e.message, showError = true) }
                }
                .onSuccess {
                    _state.update { it.copy(isWorking = false) }
                }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null, showError = false) }
    }
}
