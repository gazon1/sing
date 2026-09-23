package com.singularity.todo.feature.sync.presentation

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.sync.SyncEngineStatus
import com.singularity.todo.core.sync.SyncPrefs
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.core.sync.SyncTrigger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/**
 * MVI Intent for sync screen.
 */
sealed interface SyncIntent {
    data object SyncNow : SyncIntent
    data class SetAutoSync(val enabled: Boolean) : SyncIntent
    data class SetInterval(val minutes: Int) : SyncIntent
    data class AcknowledgeError(val error: AppError) : SyncIntent
}

/**
 * MVI State for sync screen.
 */
data class SyncState(
    val status: SyncEngineStatus = SyncEngineStatus.Idle,
    val autoSyncEnabled: Boolean = false,
    val intervalMinutes: Int = 30,
    val lastSyncedAt: Long? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * One-shot MVI Effect for sync screen.
 */
sealed interface SyncEffect {
    data class ShowError(val message: String) : SyncEffect
    data object SyncCompleted : SyncEffect
}

/**
 * ViewModel for the sync settings / status screen.
 *
 * Architecture:
 * - [SyncState] exposes current status + settings snapshot
 * - [SyncEffect] is a one-shot event channel for snackbar/toast
 * - [SyncIntent.process] handles all user actions
 *
 * @param scope CoroutineScope — injected by Koin (viewModel scope), NOT viewModelScope.
 *              See [singularity-todo-coroutine-scopes] skill.
 */
class SyncViewModel(
    private val repository: SyncRepository,
    private val prefs: SyncPrefs,
    private val scope: AutoCloseableCoroutineScope,
) : ViewModel() {

    init {
        addCloseable(scope)
    }

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<SyncEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<SyncEffect> = _effects.asSharedFlow()

    /**
     * Debounce flag: suppress snackbar while a sync is in progress
     * (errors are shown only after the sync completes).
     */
    private var allowSnackbarOnFailure = false

    init {
        // Observe sync engine status
        scope.launch {
            repository.status.collect { status ->
                val previous = _state.value.status
                _state.update { it.copy(status = status) }

                // Enable snackbar after first transition away from running state
                if (previous.isRunning() && !status.isRunning()) {
                    allowSnackbarOnFailure = true
                }
                if (status.isRunning()) {
                    allowSnackbarOnFailure = false
                }

                // Show error as snackbar after non-running completion
                if (!status.isRunning() && allowSnackbarOnFailure) {
                    val err = (status as? SyncEngineStatus.Failure)?.error
                    if (err != null) {
                        _effects.emit(SyncEffect.ShowError(err.message ?: "Sync failed"))
                    } else if (status is SyncEngineStatus.Idle && previous.isRunning()) {
                        // Successful completion
                        _effects.emit(SyncEffect.SyncCompleted)
                    }
                }
            }
        }

        // Observe last successful sync timestamp
        scope.launch {
            repository.lastPull.collect { result ->
                result?.onSuccess {
                    _state.update { st -> st.copy(lastSyncedAt = prefs.lastSuccessfulSyncAt) }
                }
            }
        }
    }

    fun process(intent: SyncIntent) {
        when (intent) {
            is SyncIntent.SyncNow -> syncNow()
            is SyncIntent.SetAutoSync -> setAutoSync(intent.enabled)
            is SyncIntent.SetInterval -> setInterval(intent.minutes)
            is SyncIntent.AcknowledgeError -> acknowledgeError()
        }
    }

    private fun syncNow() {
        scope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                repository.syncOnce()
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun setAutoSync(enabled: Boolean) {
        prefs.setAutoSyncEnabled(enabled)
        _state.update { it.copy(autoSyncEnabled = enabled) }
        if (enabled) {
            repository.startScheduledSync(_state.value.intervalMinutes.minutes)
        } else {
            repository.stopScheduledSync()
        }
    }

    private fun setInterval(minutes: Int) {
        prefs.setScheduledInterval(minutes.minutes)
        _state.update { it.copy(intervalMinutes = minutes) }
        if (_state.value.autoSyncEnabled) {
            repository.startScheduledSync(minutes.minutes)
        }
    }

    private fun acknowledgeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun buildState(): SyncState = SyncState(
        autoSyncEnabled = prefs.autoSyncEnabled,
        intervalMinutes = prefs.scheduledInterval.inWholeMinutes.toInt(),
        lastSyncedAt = prefs.lastSuccessfulSyncAt,
        status = repository.status.value,
    )
}
