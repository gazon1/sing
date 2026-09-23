package com.singularity.todo.feature.calendar_sync.presentation

import com.singularity.todo.feature.calendar_sync.domain.model.CalendarSyncStatus
import com.singularity.todo.feature.calendar_sync.domain.port.CalendarProviderPort
import com.singularity.todo.feature.calendar_sync.domain.repository.CalendarSyncRepository
import com.singularity.todo.feature.calendar_sync.work.CalendarSyncWorkScheduler
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
)

/**
 * User intents for the calendar sync settings screen.
 */
sealed interface CalendarSyncIntent {
    data object LoadCalendars : CalendarSyncIntent
    data class SetEnabled(val enabled: Boolean) : CalendarSyncIntent
    data class SelectCalendar(val calendarId: String) : CalendarSyncIntent
    data object SyncNow : CalendarSyncIntent
    data object RequestPermission : CalendarSyncIntent
    data class SetPermission(val granted: Boolean) : CalendarSyncIntent
}

class CalendarSyncViewModel(
    private val syncRepo: CalendarSyncRepository,
    private val calendarProvider: CalendarProviderPort,
    private val scheduler: CalendarSyncWorkScheduler,
) {
    private val _state = MutableStateFlow(CalendarSyncUiState())
    val state: StateFlow<CalendarSyncUiState> = _state.asStateFlow()

    init {
        // Mirror repository flows into UI state
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch {
            combine(
                syncRepo.observeEnabled(),
                syncRepo.observeTargetCalendarId(),
                syncRepo.observeStatus(),
                syncRepo.observeLastSyncedAt(),
            ) { enabled, calendarId, status, lastAt ->
                _state.value = _state.value.copy(
                    isEnabled = enabled,
                    selectedCalendarId = calendarId,
                    status = status,
                    lastSyncedAt = lastAt,
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
            is CalendarSyncIntent.RequestPermission -> { /* handled by the UI */ }
            is CalendarSyncIntent.SetPermission -> setPermission(intent.granted)
        }
    }

    private fun loadCalendars() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch {
            _state.value = _state.value.copy(isLoading = true)
            calendarProvider.getAvailableCalendars()
                .onSuccess { calendars ->
                    _state.value = _state.value.copy(
                        availableCalendars = calendars,
                        isLoading = false,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(isLoading = false)
                }
        }
    }

    private fun setEnabled(enabled: Boolean) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch {
            syncRepo.setEnabled(enabled)
            if (enabled) {
                scheduler.enqueueSync()
            } else {
                scheduler.cancelSync()
            }
        }
    }

    private fun selectCalendar(calendarId: String) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch {
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
}
