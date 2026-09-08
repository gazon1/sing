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

    private val _events = MutableSharedFlow<BackupUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<BackupUiEvent> = _events.asSharedFlow()

    private val effectiveUserId: UserId
        get() = AuthDomain.effectiveUserId(authRepository.session.value)

    init {
        scope.launch {
            repository.backups.collect { backups ->
                _state.update { it.copy(backups = backups) }
            }
        }
    }

    fun export(destPath: String) {
        scope.launch {
            _state.update { it.copy(isWorking = true) }
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
                }
        }
    }
}
