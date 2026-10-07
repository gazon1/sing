package com.singularity.todo.feature.sync.presentation

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.toAppError
import com.singularity.todo.core.observability.reportingScope
import com.singularity.todo.core.sync.ConnectionTestResult
import com.singularity.todo.core.sync.SyncEngineStatus
import com.singularity.todo.core.sync.SyncScope
import com.singularity.todo.core.sync.SyncScopeProvider
import com.singularity.todo.core.sync.SyncStateRepository
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    /**
     * Per-scope attachment sync preference, read from [SyncStateRepository].
     *
     * Deliberately read-only here. The row that shows it is locked while
     * [ATTACHMENTS_SYNC_TRANSPORT_AVAILABLE] is `false`, so an intent that wrote
     * this field would have no way to be dispatched — the same "fully
     * implemented, reachable from nothing" shape this change set is removing.
     * The write path is [SyncStateRepository.setAttachmentsSyncEnabled], which
     * is scoped and tested; the intent arrives with stage 2, when it can be
     * dispatched by something real.
     */
    val attachmentsSyncEnabled: Boolean = false,
)

/**
 * ViewModel for the sync settings / status screen.
 *
 * Architecture:
 * - [SyncState] exposes current status + settings snapshot
 * - Error messages are embedded in state ([SyncState.errorMessage]) — callers handle snackbar display
 * - [SyncIntent.process] handles all user actions
 *
 * ## Why the settings are observed, not read once
 *
 * The screen used to read a flat [com.singularity.todo.core.sync.SyncPrefs] and keep
 * its own copy. That made the screen's notion of "auto-sync is on" a *second* source
 * of truth: switching profile kept showing and writing the previous profile's
 * settings, and every write landed in the one global slot. Settings are now read
 * from [SyncStateRepository] for whichever [SyncScope] is current, and a write is
 * addressed to a scope rather than to the app.
 *
 * The first frame therefore carries neutral defaults and is corrected by the first
 * observation, which is why [SyncState.autoSyncEnabled] defaults to `false` rather
 * than `true`: a frame that claims auto-sync is on before anything has said so is a
 * frame that shows the user the wrong switch.
 *
 * @param scope CoroutineScope — one built from [crashReporter] unless a test supplies its own.
 *              See [singularity-todo-coroutine-scopes] skill. It is **not** injected from the
 *              graph: a scope supplied here alongside [crashReporter] is chosen independently,
 *              so nothing would guarantee the two report to the same place. That is what
 *              `NoDivergentScopeAndReporter` reports.
 */
class SyncViewModel(
    private val repository: SyncRepository,
    private val stateRepository: SyncStateRepository,
    scopeProvider: SyncScopeProvider,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<SyncState, SyncIntent, Nothing>(
        initialState = SyncState(status = repository.status.value),
        crashReporter = crashReporter,
        scope = scope,
    ) {
    private val syncMutex = Mutex()

    /**
     * The scope the screen is currently editing.
     *
     * Null until the provider emits, and null whenever the user is signed out or no
     * profile is active. Writes are dropped in that case rather than queued for a
     * scope that may never arrive — a setting changed on a screen that has no
     * subject has nowhere to go, and deferring it would apply it later to a
     * different profile than the one the user was looking at.
     */
    @Volatile
    private var currentScope: SyncScope? = null

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

        // Observe the settings of whichever scope is current.
        //
        // One `flatMapLatest`, not a `combine` of the scope with a settings flow: on
        // a profile switch the old scope's row must stop being observed immediately,
        // and a combine would keep feeding the previous profile's values in until the
        // new row emitted — which is exactly the bug this screen had.
        @OptIn(ExperimentalCoroutinesApi::class)
        vmScope.launch {
            scopeProvider.current
                .flatMapLatest { active ->
                    if (active == null) {
                        flowOf(null)
                    } else {
                        stateRepository.observe(active).map { active to it }
                    }
                }
                .collect { pair ->
                    val active = pair?.first
                    val settings = pair?.second
                    currentScope = active
                    if (settings == null) {
                        updateState { SyncState(status = it.status) }
                    } else {
                        updateState {
                            it.copy(
                                autoSyncEnabled = settings.autoSyncEnabled,
                                intervalMinutes = settings.scheduledInterval.inWholeMinutes.toInt(),
                                lastSyncedAt = settings.lastSuccessfulSyncAt,
                                attachmentsSyncEnabled = settings.attachmentsSyncEnabled,
                            )
                        }
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
                    // The status line is the whole story for the user; without this the
                    // stack trace that explains the failure existed nowhere.
                    crashReporter.report(e, SYNC_ONCE_FAILED)
                    allowSnackbarOnFailure = true
                    updateState {
                        it.copy(
                            isLoading = false,
                            status = SyncEngineStatus.Failure(e.toAppError()),
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
        val target = currentScope ?: return
        vmScope.launch {
            stateRepository.setAutoSyncEnabled(target, enabled)
        }
        updateState { it.copy(autoSyncEnabled = enabled) }
        if (enabled) {
            repository.startScheduledSync(currentState.intervalMinutes.minutes)
        } else {
            repository.stopScheduledSync()
        }
    }

    private fun setInterval(minutes: Int) {
        val target = currentScope ?: return
        vmScope.launch {
            stateRepository.setScheduledInterval(target, minutes.minutes)
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

    private companion object {
        // Machine-shaped grouping keys — these leave the device.
        const val SYNC_ONCE_FAILED = "sync.once_failed"
    }
}
