package com.singularity.todo.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.feature.ai.OpenAiConfig
import com.singularity.todo.feature.ai.TextGenPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Single ViewModel for all settings screens.
 *
 * Subscribes to individual settings flows via [viewModelScope] (cancelled
 * in [onCleared]). For tests that need a controlled dispatcher, pass an
 * explicit [scopeOverride] — typically the TestScope from `runTest`.
 *
 * The API key is intentionally NOT part of [SettingsUiState.Content] — it lives
 * in [SecureStoragePort] (hardware-backed). The Settings UI holds a local-only
 * field for the password input and writes through [processIntent].
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val secureStorage: SecureStoragePort,
    private val textGen: TextGenPort,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val log = Logger.withTag("SettingsViewModel")

    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // Debounced sinks for free-text AI fields — avoids hammering DataStore/SecureStorage on every keystroke.
    private val _aiApiKeyInput = MutableStateFlow("")
    private val _aiBaseUrlInput = MutableStateFlow("")
    private val _aiSystemPromptInput = MutableStateFlow("")

    /** When a [scopeOverride] is supplied (tests), skip debounce so tests can verify state synchronously. */
    private val debounceEnabled = scopeOverride == null

    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    init {
        log.d { "SettingsViewModel init" }
        scope.launch(Dispatchers.Unconfined) {
            val initial = SettingsUiState.Content(
                darkTheme = settings.darkTheme.first(),
                accentColor = settings.accentColor.first(),
                fontSizeScale = settings.fontSizeScale.first(),
                notificationsEnabled = settings.notificationsEnabled.first(),
                notificationSound = settings.notificationSound.first(),
                notificationVibration = settings.notificationVibration.first(),
                reminderDefault = settings.reminderDefault.first(),
                aiProvider = settings.aiProvider.first(),
                aiBaseUrl = settings.aiBaseUrl.first(),
                aiModel = settings.aiModel.first(),
                aiSystemPrompt = settings.aiSystemPrompt.first(),
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
            launchFlow(settings.aiProvider)        { a -> update { it.copy(aiProvider = a) } }
            launchFlow(settings.aiBaseUrl)         { a -> update { it.copy(aiBaseUrl = a) } }
            launchFlow(settings.aiModel)           { a -> update { it.copy(aiModel = a) } }
            launchFlow(settings.aiSystemPrompt)    { a -> update { it.copy(aiSystemPrompt = a) } }
            launchFlow(settings.workDayStartMinutes) { w -> update { it.copy(workDayStartMinutes = w) } }
            launchFlow(settings.workDayEndMinutes)   { w -> update { it.copy(workDayEndMinutes = w) } }
            launchFlow(settings.workLunchStartMinutes) { w -> update { it.copy(workLunchStartMinutes = w) } }
            launchFlow(settings.workLunchEndMinutes)   { w -> update { it.copy(workLunchEndMinutes = w) } }
            launchFlow(settings.workWeekendSat)     { w -> update { it.copy(workWeekendSat = w) } }
            launchFlow(settings.workWeekendSun)     { w -> update { it.copy(workWeekendSun = w) } }
            launchFlow(settings.greetingMorningEnd)  { g -> update { it.copy(greetingMorningEnd = g) } }
            launchFlow(settings.greetingAfternoonEnd){ g -> update { it.copy(greetingAfternoonEnd = g) } }
            launchFlow(settings.userId)            { u -> update { it.copy(userId = u) } }

            // Debounced sinks: write through to repository only after 300ms of inactivity.
            scope.launch {
                _aiApiKeyInput.debounce(300L.milliseconds).collect { value ->
                    if (value.isNotBlank()) {
                        secureStorage.write(OpenAiConfig.KEY_OPENAI, value)
                    } else {
                        secureStorage.delete(OpenAiConfig.KEY_OPENAI)
                    }
                }
            }
            scope.launch {
                _aiBaseUrlInput.debounce(300L.milliseconds).collect { value ->
                    settings.setAiBaseUrl(value)
                }
            }
            scope.launch {
                _aiSystemPromptInput.debounce(300L.milliseconds).collect { value ->
                    settings.setAiSystemPrompt(value)
                }
            }
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
        log.d { "processIntent: $intent" }
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
                    // The key never appears in UI state — only in SecureStorage.
                    // Piped through a debounced StateFlow so SecureStorage is not hit on every keystroke.
                    // In test mode (scopeOverride != null) we also write immediately so tests see the effect.
                    _aiApiKeyInput.value = intent.value
                    if (debounceEnabled.not()) {
                        if (intent.value.isNotBlank()) {
                            secureStorage.write(OpenAiConfig.KEY_OPENAI, intent.value)
                        } else {
                            secureStorage.delete(OpenAiConfig.KEY_OPENAI)
                        }
                    }
                }
                is SettingsIntent.UpdateAiProvider -> settings.setAiProvider(intent.value)
                is SettingsIntent.UpdateAiBaseUrl -> {
                    _aiBaseUrlInput.value = intent.value
                    if (debounceEnabled.not()) settings.setAiBaseUrl(intent.value)
                }
                is SettingsIntent.UpdateAiModel -> settings.setAiModel(intent.value)
                is SettingsIntent.UpdateAiSystemPrompt -> {
                    _aiSystemPromptInput.value = intent.value
                    if (debounceEnabled.not()) settings.setAiSystemPrompt(intent.value)
                }
                SettingsIntent.TestAiConnection -> testConnection()
                SettingsIntent.FetchAiModels -> fetchAiModels()
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

    private fun testConnection() {
        scope.launch(Dispatchers.Unconfined) {
            update { it.copy(aiTestResult = AiTestResult.Testing) }
            val start = clock()
            val cfg = OpenAiConfig.resolve(secureStorage, settings)
            if (!cfg.apiKey.isConfigured) {
                update { it.copy(aiTestResult = AiTestResult.Error("API key not configured")) }
                return@launch
            }
            val result = textGen.generate(
                prompt = "ping",
                systemPrompt = "You are a connectivity probe. Reply with the single word: pong.",
                model = cfg.defaultModelId,
            )
            val latency = clock() - start
            update {
                it.copy(
                    aiTestResult = result.fold(
                        onSuccess = { AiTestResult.Ok(latency) },
                        onFailure = { e -> AiTestResult.Error(e.message ?: "Unknown error") },
                    )
                )
            }
        }
    }

    private fun fetchAiModels() {
        scope.launch(Dispatchers.Unconfined) {
            update { it.copy(isFetchingAiModels = true, fetchAiModelsError = null) }
            val cfg = OpenAiConfig.resolve(secureStorage, settings)
            if (!cfg.apiKey.isConfigured) {
                update { it.copy(isFetchingAiModels = false, fetchAiModelsError = "API key not configured") }
                return@launch
            }
            val result = textGen.listModels(cfg.baseUrl, cfg.apiKey.value)
            update {
                it.copy(
                    isFetchingAiModels = false,
                    aiModels = result.getOrDefault(emptyList()),
                    fetchAiModelsError = result.exceptionOrNull()?.message,
                )
            }
        }
    }
}