package com.singularity.todo.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Single ViewModel for all settings screens.
 *
 * Subscribes to 18 individual settings flows via [viewModelScope] (cancelled
 * in [onCleared]). For tests that need a controlled dispatcher, pass an
 * explicit [scopeOverride] — typically the TestScope from `runTest`.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val secureStorage: SecureStoragePort,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    init {
        // Dispatchers.Unconfined ensures the initial snapshot + per-flow collectors
        // start before the first UI subscription — important for tests with
        // TestScope where the dispatcher's queue is lazy by default.
        scope.launch(Dispatchers.Unconfined) {
            val initial = SettingsUiState.Content(
                darkTheme = settings.darkTheme.first(),
                accentColor = settings.accentColor.first(),
                fontSizeScale = settings.fontSizeScale.first(),
                notificationsEnabled = settings.notificationsEnabled.first(),
                notificationSound = settings.notificationSound.first(),
                notificationVibration = settings.notificationVibration.first(),
                reminderDefault = settings.reminderDefault.first(),
                aiApiKey = settings.aiApiKey.first(),
                aiBaseUrl = settings.aiBaseUrl.first(),
                aiModel = settings.aiModel.first(),
                workDayStartMinutes = settings.workDayStartMinutes.first(),
                workDayEndMinutes = settings.workDayEndMinutes.first(),
                workLunchStartMinutes = settings.workLunchStartMinutes.first(),
                workLunchEndMinutes = settings.workLunchEndMinutes.first(),
                workWeekendSat = settings.workWeekendSat.first(),
                workWeekendSun = settings.workWeekendSun.first(),
                greetingMorningEnd = settings.greetingMorningEnd.first(),
                greetingAfternoonEnd = settings.greetingAfternoonEnd.first(),
                userId = settings.userId.first(),
            )
            _uiState.value = initial

            launchFlow(settings.darkTheme)         { d -> update { it.copy(darkTheme = d) } }
            launchFlow(settings.accentColor)       { a -> update { it.copy(accentColor = a) } }
            launchFlow(settings.fontSizeScale)     { f -> update { it.copy(fontSizeScale = f) } }
            launchFlow(settings.notificationsEnabled) { n -> update { it.copy(notificationsEnabled = n) } }
            launchFlow(settings.notificationSound)  { n -> update { it.copy(notificationSound = n) } }
            launchFlow(settings.notificationVibration) { n -> update { it.copy(notificationVibration = n) } }
            launchFlow(settings.reminderDefault)   { r -> update { it.copy(reminderDefault = r) } }
            launchFlow(settings.aiApiKey)          { a -> update { it.copy(aiApiKey = a) } }
            launchFlow(settings.aiBaseUrl)         { a -> update { it.copy(aiBaseUrl = a) } }
            launchFlow(settings.aiModel)           { a -> update { it.copy(aiModel = a) } }
            launchFlow(settings.workDayStartMinutes) { w -> update { it.copy(workDayStartMinutes = w) } }
            launchFlow(settings.workDayEndMinutes)   { w -> update { it.copy(workDayEndMinutes = w) } }
            launchFlow(settings.workLunchStartMinutes) { w -> update { it.copy(workLunchStartMinutes = w) } }
            launchFlow(settings.workLunchEndMinutes)   { w -> update { it.copy(workLunchEndMinutes = w) } }
            launchFlow(settings.workWeekendSat)     { w -> update { it.copy(workWeekendSat = w) } }
            launchFlow(settings.workWeekendSun)     { w -> update { it.copy(workWeekendSun = w) } }
            launchFlow(settings.greetingMorningEnd)  { g -> update { it.copy(greetingMorningEnd = g) } }
            launchFlow(settings.greetingAfternoonEnd){ g -> update { it.copy(greetingAfternoonEnd = g) } }
            launchFlow(settings.userId)            { u -> update { it.copy(userId = u) } }
        }
    }

    private fun update(update: (SettingsUiState.Content) -> SettingsUiState.Content) {
        val current = _uiState.value
        if (current is SettingsUiState.Content) _uiState.value = update(current)
    }

    private fun <T> launchFlow(flow: kotlinx.coroutines.flow.Flow<T>, onEach: (T) -> Unit) {
        scope.launch(Dispatchers.Unconfined) { flow.collect { onEach(it) } }
    }

    /** Process a settings intent, updating DataStore (or SecureStorage for secrets). */
    fun processIntent(intent: SettingsIntent) {
        scope.launch(Dispatchers.Unconfined) {
            when (intent) {
                is SettingsIntent.UpdateDarkTheme -> settings.setDarkTheme(intent.value)
                is SettingsIntent.UpdateAccentColor -> settings.setAccentColor(intent.value)
                is SettingsIntent.UpdateFontSizeScale -> settings.setFontSizeScale(intent.value)
                is SettingsIntent.UpdateNotificationsEnabled -> settings.setNotificationsEnabled(intent.value)
                is SettingsIntent.UpdateNotificationSound -> settings.setNotificationSound(intent.value)
                is SettingsIntent.UpdateNotificationVibration -> settings.setNotificationVibration(intent.value)
                is SettingsIntent.UpdateReminderDefault -> settings.setReminderDefault(intent.value)
                is SettingsIntent.UpdateAiApiKey -> {
                    if (intent.value.isNotBlank()) {
                        secureStorage.write("ai_key_openai", intent.value)
                    } else {
                        secureStorage.delete("ai_key_openai")
                    }
                    settings.setAiApiKey(intent.value)
                }
                is SettingsIntent.UpdateAiBaseUrl -> settings.setAiBaseUrl(intent.value)
                is SettingsIntent.UpdateAiModel -> settings.setAiModel(intent.value)
                is SettingsIntent.UpdateWorkDayStart -> settings.setWorkDayStartMinutes(intent.minutes)
                is SettingsIntent.UpdateWorkDayEnd -> settings.setWorkDayEndMinutes(intent.minutes)
                is SettingsIntent.UpdateWorkLunchStart -> settings.setWorkLunchStartMinutes(intent.minutes)
                is SettingsIntent.UpdateWorkLunchEnd -> settings.setWorkLunchEndMinutes(intent.minutes)
                is SettingsIntent.UpdateWorkWeekendSat -> settings.setWorkWeekendSat(intent.value)
                is SettingsIntent.UpdateWorkWeekendSun -> settings.setWorkWeekendSun(intent.value)
                is SettingsIntent.UpdateGreetingMorningEnd -> settings.setGreetingMorningEnd(intent.hour)
                is SettingsIntent.UpdateGreetingAfternoonEnd -> settings.setGreetingAfternoonEnd(intent.hour)
            }
        }
    }
}
