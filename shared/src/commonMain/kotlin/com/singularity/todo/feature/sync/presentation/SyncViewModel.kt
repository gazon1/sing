package com.singularity.todo.feature.sync.presentation

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.sync.ConnectionTestResult
import com.singularity.todo.core.sync.SyncEngineStatus
import com.singularity.todo.core.sync.SyncPrefs
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.core.sync.SyncTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.minutes

/**
 * MVI Intent for sync screen.
 */
sealed interface SyncIntent {
    data object SyncNow : SyncIntent
    data class SetAutoSync(val enabled: Boolean) : SyncIntent
    data class SetInterval(val minutes: Int) : SyncIntent
    data object AcknowledgeError : SyncIntent
    data object TestConnection : SyncIntent
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
    val isTestingConnection: Boolean = false,
    val connectionTestResult: ConnectionTestResult? = null,
)

/**
 * ViewModel for the sync settings / status screen.
 *
 * Architecture:
 * - [SyncState] exposes current status + settings snapshot
 * - Error messages are embedded in state ([SyncState.errorMessage]) — callers handle snackbar display
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
    /** Exposed for tests — cancel to terminate infinite collectors before test scope cleanup. */
    val vmScope: AutoCloseableCoroutineScope = scope

    init {
        addCloseable(scope)
    }

    private val syncMutex = Mutex()

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

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
                        // Only set errorMessage if not already showing a connection-test result
                        if (_state.value.connectionTestResult == null) {
                            _state.update { it.copy(errorMessage = err.message ?: "Sync failed") }
                        }
                    }
                    // else: successful completion — no error to show
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
            is SyncIntent.TestConnection -> testConnection()
        }
    }

    private fun syncNow() {
        scope.launch {
            syncMutex.withLock {
                // Debounce: ignore if already syncing
                if (_state.value.isLoading || _state.value.status.isRunning()) return@launch
                _state.update { it.copy(isLoading = true, errorMessage = null, connectionTestResult = null) }
                try {
                    repository.syncOnce()
                } catch (e: Throwable) {
                    // Exception from syncOnce() (e.g. getOrThrow() on a Failure Result).
                    // Ensure the snackbar shows after this sync completes.
                    allowSnackbarOnFailure = true
                    _state.update { it.copy(isLoading = false, status = SyncEngineStatus.Failure(e as? AppError ?: AppError.Unknown(e))) }
                    return@launch
                } finally {
                    _state.update { it.copy(isLoading = false) }
                }
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
        _state.update { it.copy(errorMessage = null, connectionTestResult = null) }
    }

    private fun testConnection() {
        scope.launch {
            _state.update { it.copy(isTestingConnection = true, connectionTestResult = null, errorMessage = null) }
            val result = repository.testConnection()
            _state.update { it.copy(isTestingConnection = false, connectionTestResult = result) }
        }
    }

    private fun buildState(): SyncState = SyncState(
        autoSyncEnabled = prefs.autoSyncEnabled,
        intervalMinutes = prefs.scheduledInterval.inWholeMinutes.toInt(),
        lastSyncedAt = prefs.lastSuccessfulSyncAt,
        status = repository.status.value,
    )
}
