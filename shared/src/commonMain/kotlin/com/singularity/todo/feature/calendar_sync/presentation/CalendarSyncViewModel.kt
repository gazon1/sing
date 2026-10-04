package com.singularity.todo.feature.calendar_sync.presentation

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.core.ui.featureSlot.combineStates
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarAppInfo
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncStatus
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarAppQueries
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarSyncRepository
import com.singularity.todo.feature.calendar_sync.sync.CalendarSyncOrchestrator
import com.singularity.todo.feature.calendar_sync.sync.SyncSource
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * UI state for the calendar sync settings screen.
 */
data class CalendarSyncUiState(
    val isEnabled: Boolean = false,
    val availableCalendars: Map<String, String> = emptyMap(),
    val selectedCalendarId: String? = null,
    val status: CalendarSyncStatus = CalendarSyncStatus.Disabled,
    val lastSyncedAt: Long? = null,
    val isLoading: Boolean = false,
    val hasPermission: Boolean = false,
    /** List of installed calendar apps for the picker. */
    val availableApps: List<CalendarAppInfo> = emptyList(),
    /** Currently selected calendar app package (null = system default). */
    val selectedAppPackage: String? = null,
)

/**
 * User intents for the calendar sync settings screen.
 */
sealed interface CalendarSyncIntent : MviIntent {
    data object LoadCalendars : CalendarSyncIntent
    data class SetEnabled(val enabled: Boolean) : CalendarSyncIntent
    data class SelectCalendar(val calendarId: String) : CalendarSyncIntent
    data object SyncNow : CalendarSyncIntent

    /** Handled by the UI layer (rememberLauncherForActivityResult). */
    data object RequestPermission : CalendarSyncIntent
    data class SetPermission(val granted: Boolean) : CalendarSyncIntent

    /** Select which calendar app to sync to (null = system default). */
    data class SelectAppPackage(val packageName: String?) : CalendarSyncIntent
}

/**
 * Canonical 6-arg ViewModel for calendar sync settings.
 *
 * - [syncRepo] — settings repository (DataStore-backed)
 * - [calendarProvider] — system calendar provider (ContentResolver on Android)
 * - [scheduler] — WorkManager scheduler (used for cancel only)
 * - [appQueries] — queries installed calendar apps for the picker
 * - [orchestrator] — debounced sync orchestrator (hands off to scheduler)
 * - [scope] — [AutoCloseableCoroutineScope] for launching concurrent operations
 */
class CalendarSyncViewModel(
    private val syncRepo: CalendarSyncRepository,
    private val calendarProvider: CalendarProviderPort,
    private val scheduler: com.singularity.todo.feature.calendar_sync.work.CalendarSyncWorkScheduler,
    private val appQueries: CalendarAppQueries,
    private val orchestrator: CalendarSyncOrchestrator,
    private val crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope,
) : MviViewModel<CalendarSyncUiState, CalendarSyncIntent, Nothing>(
        initialState = CalendarSyncUiState(),
        crashReporter = crashReporter,
        scope = scope,
    ) {
    // MviViewModel handles addCloseable(scope) — no manual call needed

    init {
        // The transform projects; the collector applies. Writing state from inside a
        // `combine` transform is the anti-pattern `NoCombineSideEffect` exists for — the
        // write re-fires on every upstream emission instead of being applied once per
        // emission to a value the transform returned. The transform returns the reducer
        // so the flow's value type stays `CalendarSyncUiState.Content` with no
        // hand-written intermediate class.
        vmScope.launch {
            combineStates(
                syncRepo.observeEnabled(),
                syncRepo.observeTargetCalendarId(),
                syncRepo.observeStatus(),
                syncRepo.observeLastSyncedAt(),
                syncRepo.observeTargetAppPackage(),
            ) { enabled, calendarId, status, lastAt, appPkg ->
                { content: CalendarSyncUiState ->
                    content.copy(
                        isEnabled = enabled,
                        selectedCalendarId = calendarId,
                        status = status,
                        lastSyncedAt = lastAt,
                        selectedAppPackage = appPkg,
                    )
                }
            }.collect { reduce -> updateState { reduce(it) } }
        }
    }

    override fun onIntent(intent: CalendarSyncIntent) {
        when (intent) {
            is CalendarSyncIntent.LoadCalendars -> loadCalendars()
            is CalendarSyncIntent.SetEnabled -> setEnabled(intent.enabled)
            is CalendarSyncIntent.SelectCalendar -> selectCalendar(intent.calendarId)
            CalendarSyncIntent.SyncNow -> syncNow()
            is CalendarSyncIntent.RequestPermission -> { /* UI layer */ }
            is CalendarSyncIntent.SetPermission -> setPermission(intent.granted)
            is CalendarSyncIntent.SelectAppPackage -> selectAppPackage(intent.packageName)
        }
    }

    private fun loadCalendars() {
        vmScope.launch {
            updateState { it.copy(isLoading = true) }

            // Load calendar apps and calendars in parallel
            val apps = try {
                appQueries.listInstalled()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Swallowed into an empty list, so the picker silently shows no apps and the
                // only trace was a warn line nothing reads.
                Logger.w(e) { "Failed to list installed calendar apps" }
                crashReporter.report(e, LIST_APPS_FAILED)
                emptyList()
            }
            val calendarsResult = calendarProvider.getAvailableCalendars()

            calendarsResult
                .onSuccess { calendars ->
                    updateState {
                        it.copy(
                            availableCalendars = calendars,
                            availableApps = apps,
                            isLoading = false,
                        )
                    }
                }
                .onFailure {
                    updateState {
                        it.copy(
                            availableApps = apps,
                            isLoading = false,
                        )
                    }
                }
        }
    }

    private fun setEnabled(enabled: Boolean) {
        vmScope.launch {
            syncRepo.setEnabled(enabled)
            if (enabled) {
                orchestrator.requestSync(SyncSource.ConfigChanged)
            } else {
                scheduler.cancelSync()
            }
            updateState { it.copy(isEnabled = enabled) }
        }
    }

    private fun selectCalendar(calendarId: String) {
        vmScope.launch {
            syncRepo.setTargetCalendarId(calendarId)
            updateState { it.copy(selectedCalendarId = calendarId) }
            if (currentState.isEnabled) {
                orchestrator.requestSync(SyncSource.ConfigChanged)
            }
        }
    }

    private fun syncNow() {
        orchestrator.requestSync(SyncSource.Manual)
    }

    private fun setPermission(granted: Boolean) {
        updateState { it.copy(hasPermission = granted) }
        if (granted) {
            loadCalendars()
        }
    }

    private fun selectAppPackage(packageName: String?) {
        vmScope.launch {
            syncRepo.setTargetAppPackage(packageName)
            updateState { it.copy(selectedAppPackage = packageName) }
            if (currentState.isEnabled) {
                orchestrator.requestSync(SyncSource.ConfigChanged)
            }
        }
    }

    private companion object {
        // Machine-shaped grouping keys — these leave the device.
        const val LIST_APPS_FAILED = "calendar_sync.list_apps_failed"
    }
}
