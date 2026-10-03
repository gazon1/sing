package com.singularity.todo.feature.sync.presentation

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.toMessage
import com.singularity.todo.core.sync.ConnectionTestResult
import com.singularity.todo.core.sync.SyncEngineStatus
import com.singularity.todo.core.sync.SyncPrefs
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.minutes

/**
 * MVI Intent for sync screen.
 */
sealed interface SyncIntent : MviIntent {
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
 * @param scope CoroutineScope — injected by Koin (view model scope), NOT viewModelScope.
 *              See [singularity-todo-coroutine-scopes] skill.
 */
class SyncViewModel(
    private val repository: SyncRepository,
    private val prefs: SyncPrefs,
    scope: AutoCloseableCoroutineScope,
) : MviViewModel<SyncState, SyncIntent, Nothing>(
        initialState = SyncState(
            autoSyncEnabled = prefs.autoSyncEnabled,
            intervalMinutes = prefs.scheduledInterval.inWholeMinutes.toInt(),
            lastSyncedAt = prefs.lastSuccessfulSyncAt,
            status = repository.status.value,
        ),
        scope = scope,
    ) {
    private val syncMutex = Mutex()

    /**
     * Debounce flag: suppress snackbar while a sync is in progress
     * (errors are shown only after the sync completes).
     */
    private var allowSnackbarOnFailure = false

    init {
        // Observe sync engine status
        vmScope.launch {
            repository.status.collect { status ->
                val previous = currentState.status
                updateState { it.copy(status = status) }

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
                        if (currentState.connectionTestResult == null) {
                            updateState { it.copy(errorMessage = err.message ?: "Sync failed") }
                        }
                    }
                    // else: successful completion — no error to show
                }
            }
        }

        // Observe last successful sync timestamp
        vmScope.launch {
            repository.lastPull.collect { result ->
                result?.onSuccess {
                    updateState { st -> st.copy(lastSyncedAt = prefs.lastSuccessfulSyncAt) }
                }
            }
        }
    }

    override fun onIntent(intent: SyncIntent) {
        when (intent) {
            is SyncIntent.SyncNow -> syncNow()
            is SyncIntent.SetAutoSync -> setAutoSync(intent.enabled)
            is SyncIntent.SetInterval -> setInterval(intent.minutes)
            SyncIntent.AcknowledgeError -> acknowledgeError()
            SyncIntent.TestConnection -> testConnection()
        }
    }

    private fun syncNow() {
        vmScope.launch {
            // Debounce BEFORE acquiring the mutex: a second SyncNow dispatched while
            // the first sync is suspended must return immediately. Checking only inside
            // the lock is too late — by the time the second call acquires the mutex,
            // the first has already cleared isLoading in its finally block.
            if (currentState.isLoading || currentState.status.isRunning()) return@launch
            syncMutex.withLock {
                // Re-check under the lock to guard against concurrent acquisition.
                if (currentState.isLoading || currentState.status.isRunning()) return@launch
                updateState { it.copy(isLoading = true, errorMessage = null, connectionTestResult = null) }
                try {
                    repository.syncOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Exception from syncOnce() (e.g. getOrThrow() on a Failure Result).
                    // Ensure the snackbar shows after this sync completes.
                    allowSnackbarOnFailure = true
                    updateState {
                        it.copy(
                            isLoading = false,
                            status = SyncEngineStatus.Failure(e as? AppError ?: AppError.Unknown(e.toMessage())),
                        )
                    }
                    return@launch
                } finally {
                    updateState { it.copy(isLoading = false) }
                }
            }
        }
    }

    private fun setAutoSync(enabled: Boolean) {
        vmScope.launch {
            prefs.setAutoSyncEnabled(enabled)
        }
        updateState { it.copy(autoSyncEnabled = enabled) }
        if (enabled) {
            repository.startScheduledSync(currentState.intervalMinutes.minutes)
        } else {
            repository.stopScheduledSync()
        }
    }

    private fun setInterval(minutes: Int) {
        vmScope.launch {
            prefs.setScheduledInterval(minutes.minutes)
        }
        updateState { it.copy(intervalMinutes = minutes) }
        if (currentState.autoSyncEnabled) {
            repository.startScheduledSync(minutes.minutes)
        }
    }

    private fun acknowledgeError() {
        updateState { it.copy(errorMessage = null, connectionTestResult = null) }
    }

    private fun testConnection() {
        vmScope.launch {
            updateState { it.copy(isTestingConnection = true, connectionTestResult = null, errorMessage = null) }
            val result = repository.testConnection()
            updateState { it.copy(isTestingConnection = false, connectionTestResult = result) }
        }
    }

    private fun buildInitialState(): SyncState = SyncState(
        autoSyncEnabled = prefs.autoSyncEnabled,
        intervalMinutes = prefs.scheduledInterval.inWholeMinutes.toInt(),
        lastSyncedAt = prefs.lastSuccessfulSyncAt,
        status = repository.status.value,
    )
}
