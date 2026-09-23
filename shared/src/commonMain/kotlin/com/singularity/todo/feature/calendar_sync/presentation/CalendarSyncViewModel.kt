package com.singularity.todo.feature.calendar_sync.presentation

import com.singularity.todo.feature.calendar_sync.data.CalendarAppInfo
import com.singularity.todo.feature.calendar_sync.data.CalendarAppQueries
import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncStatus
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendar_sync.domain.repository.CalendarSyncRepository
import com.singularity.todo.feature.calendar_sync.work.CalendarSyncWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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
sealed interface CalendarSyncIntent {
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
 * Canonical 5-arg ViewModel for calendar sync settings.
 *
 * - [syncRepo] — settings repository (DataStore-backed)
 * - [calendarProvider] — system calendar provider (ContentResolver on Android)
 * - [scheduler] — WorkManager scheduler
 * - [appQueries] — queries installed calendar apps for the picker
 * - [scope] — CoroutineScope for launching concurrent operations
 */
class CalendarSyncViewModel(
    private val syncRepo: CalendarSyncRepository,
    private val calendarProvider: CalendarProviderPort,
    private val scheduler: CalendarSyncWorkScheduler,
    private val appQueries: CalendarAppQueries,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(CalendarSyncUiState())
    val state: StateFlow<CalendarSyncUiState> = _state.asStateFlow()

    init {
        scope.launch {
            combine(
                syncRepo.observeEnabled(),
                syncRepo.observeTargetCalendarId(),
                syncRepo.observeStatus(),
                syncRepo.observeLastSyncedAt(),
                syncRepo.observeTargetAppPackage(),
            ) { enabled, calendarId, status, lastAt, appPkg ->
                _state.value = _state.value.copy(
                    isEnabled = enabled,
                    selectedCalendarId = calendarId,
                    status = status,
                    lastSyncedAt = lastAt,
                    selectedAppPackage = appPkg,
                )
            }.collect {}
        }
    }

    fun processIntent(intent: CalendarSyncIntent) {
        when (intent) {
            is CalendarSyncIntent.LoadCalendars -> loadCalendars()
            is CalendarSyncIntent.SetEnabled -> setEnabled(intent.enabled)
            is CalendarSyncIntent.SelectCalendar -> selectCalendar(intent.calendarId)
            is CalendarSyncIntent.SyncNow -> syncNow()
            is CalendarSyncIntent.RequestPermission -> { /* UI layer */ }
            is CalendarSyncIntent.SetPermission -> setPermission(intent.granted)
            is CalendarSyncIntent.SelectAppPackage -> selectAppPackage(intent.packageName)
        }
    }

    private fun loadCalendars() {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true)

            // Load calendar apps and calendars in parallel
            val apps = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    appQueries.listInstalled()
                }
            } catch (_: Exception) {
                emptyList()
            }
            val calendarsResult = calendarProvider.getAvailableCalendars()

            calendarsResult
                .onSuccess { calendars ->
                    _state.value = _state.value.copy(
                        availableCalendars = calendars,
                        availableApps = apps,
                        isLoading = false,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        availableApps = apps,
                        isLoading = false,
                    )
                }
        }
    }

    private fun setEnabled(enabled: Boolean) {
        scope.launch {
            syncRepo.setEnabled(enabled)
            if (enabled) {
                scheduler.enqueueSync()
            } else {
                scheduler.cancelSync()
            }
        }
    }

    private fun selectCalendar(calendarId: String) {
        scope.launch {
            syncRepo.setTargetCalendarId(calendarId)
            if (_state.value.isEnabled) {
                scheduler.enqueueSync()
            }
        }
    }

    private fun syncNow() {
        scheduler.enqueueSync()
    }

    private fun setPermission(granted: Boolean) {
        _state.value = _state.value.copy(hasPermission = granted)
        if (granted) {
            loadCalendars()
        }
    }

    private fun selectAppPackage(packageName: String?) {
        scope.launch {
            syncRepo.setTargetAppPackage(packageName)
            if (_state.value.isEnabled) {
                scheduler.enqueueSync()
            }
        }
    }
}
