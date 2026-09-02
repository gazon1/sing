package com.singularity.todo.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.auth.AuthDomain
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.backup.BackupMetadata
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.BackupResult
import com.singularity.todo.core.backup.RestoreResult
import com.singularity.todo.core.backup.exportOptions
import com.singularity.todo.core.backup.importOptions
import com.singularity.todo.feature.tasks.UserId
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
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private val effectiveUserId: UserId
        get() = AuthDomain.effectiveUserId(authRepository.session.value)

    init {
        viewModelScope.launch {
            repository.backups.collect { backups ->
                _state.update { it.copy(backups = backups) }
            }
        }
    }

    fun export(destPath: String) {
        viewModelScope.launch {
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

    fun import(sourcePath: String) {
        viewModelScope.launch {
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
        viewModelScope.launch {
            repository.delete(backupId)
                .onFailure { e ->
                    _state.update { it.copy(error = e.message, showError = true) }
                }
        }
    }

    fun push(backupId: com.singularity.todo.core.backup.BackupId) {
        viewModelScope.launch {
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
