package com.singularity.todo.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.feature.ai.AiSettingsContributor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings screen ViewModel.
 *
 * Простой подход:
 * - UI state = combine всех contributors + settings flows
 * - Нет reactive snapshot — просто читаем .value синхронно
 * - processIntent обновляет state напрямую после записи в repository
 */
class SettingsViewModel(
    private val contributors: Set<SettingsContributor<*, *>>,
    private val settings: SettingsRepository,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val aiContributor: AiSettingsContributor?
        @Suppress("DEPRECATION")
        get() =
            contributors.filterIsInstance(AiSettingsContributor::class.java).firstOrNull()

    // ─── State ─────────────────────────────────────────────────────────────

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<SettingsUiState> get() = _state

    // ─── Init ─────────────────────────────────────────────────────────────

    init {
        scope.launch {
            _state.value = buildState()
        }
    }

    // ─── Build state (read .value of MutableStateFlows) ─────────────────

    private fun buildState(): SettingsUiState {
        val appearance = contributors
            .filterIsInstance<SettingsContributor<SettingsSection.Appearance, *>>()
            .firstOrNull()
            ?.observe()
            ?.let { (it as? MutableStateFlow<*>)?.value as? SettingsSection.Appearance }
            ?: SettingsSection.Appearance(
                darkTheme = settings.darkTheme.let { (it as? MutableStateFlow)?.value ?: false },
                accentColor = settings.accentColor.let { (it as? MutableStateFlow)?.value ?: "blue" },
                fontSizeScale = settings.fontSizeScale.let { (it as? MutableStateFlow)?.value ?: 1f },
            )

        val ai =
            aiContributor?.observe()?.let { (it as? MutableStateFlow<*>)?.value as? SettingsSection.Ai }
                ?: SettingsSection.Ai()

        return SettingsUiState.Content(
            appearance = appearance,
            ai = ai,
            darkTheme = appearance.darkTheme,
            accentColor = appearance.accentColor,
            fontSizeScale = appearance.fontSizeScale,
            notificationsEnabled = settings.notificationsEnabled.let { (it as? MutableStateFlow)?.value ?: true },
            notificationSound = settings.notificationSound.let { (it as? MutableStateFlow)?.value ?: true },
            notificationVibration = settings.notificationVibration.let { (it as? MutableStateFlow)?.value ?: true },
            reminderDefault = settings.reminderDefault.let {
                (it as? MutableStateFlow)?.value
                    ?: com.singularity.todo.core.reminders.ReminderOffset.AT_DUE
            },
            workDayStartMinutes = settings.workDayStartMinutes.let { (it as? MutableStateFlow)?.value ?: 540 },
            workDayEndMinutes = settings.workDayEndMinutes.let { (it as? MutableStateFlow)?.value ?: 1080 },
            workLunchStartMinutes = settings.workLunchStartMinutes.let { (it as? MutableStateFlow)?.value ?: 720 },
            workLunchEndMinutes = settings.workLunchEndMinutes.let { (it as? MutableStateFlow)?.value ?: 780 },
            workWeekendSat = settings.workWeekendSat.let { (it as? MutableStateFlow)?.value ?: false },
            workWeekendSun = settings.workWeekendSun.let { (it as? MutableStateFlow)?.value ?: false },
            greetingMorningEnd = settings.greetingMorningEnd.let { (it as? MutableStateFlow)?.value ?: 12 },
            greetingAfternoonEnd = settings.greetingAfternoonEnd.let { (it as? MutableStateFlow)?.value ?: 18 },
            userId = settings.userId.let { (it as? MutableStateFlow)?.value ?: "anonymous" },
            aiProvider = ai.provider.id,
            aiBaseUrl = ai.baseUrl,
            aiModel = ai.model,
            aiSystemPrompt = ai.systemPrompt,
            aiTestResult = ai.testResult,
            aiModels = ai.models,
            isFetchingAiModels = ai.isFetchingModels,
            fetchAiModelsError = ai.fetchModelsError,
        )
    }

    // ─── Intent ───────────────────────────────────────────────────────

    fun processIntent(intent: SettingsIntent) {
        scope.launch {
            when (intent) {
                is SettingsIntent.Appearance.UpdateDarkTheme -> {
                    settings.setDarkTheme(intent.value)
                    updateState {
                        it.copy(
                            appearance = it.appearance.copy(darkTheme = intent.value),
                            darkTheme = intent.value,
                        )
                    }
                }

                is SettingsIntent.Appearance.UpdateAccentColor -> {
                    settings.setAccentColor(intent.value)
                    updateState {
                        it.copy(
                            appearance = it.appearance.copy(accentColor = intent.value),
                            accentColor = intent.value,
                        )
                    }
                }

                is SettingsIntent.Appearance.UpdateFontSizeScale -> {
                    settings.setFontSizeScale(intent.value)
                    updateState {
                        it.copy(
                            appearance = it.appearance.copy(fontSizeScale = intent.value),
                            fontSizeScale = intent.value,
                        )
                    }
                }

                is SettingsIntent.Notifications.UpdateEnabled -> {
                    settings.setNotificationsEnabled(intent.value)
                    updateState { it.copy(notificationsEnabled = intent.value) }
                }

                is SettingsIntent.Notifications.UpdateSound -> {
                    settings.setNotificationSound(intent.value)
                    updateState { it.copy(notificationSound = intent.value) }
                }

                is SettingsIntent.Notifications.UpdateVibration -> {
                    settings.setNotificationVibration(intent.value)
                    updateState { it.copy(notificationVibration = intent.value) }
                }

                is SettingsIntent.Notifications.UpdateReminderDefault -> {
                    settings.setReminderDefault(intent.value)
                    updateState { it.copy(reminderDefault = intent.value) }
                }

                is SettingsIntent.WorkSchedule.UpdateWorkDayStart -> {
                    settings.setWorkDayStartMinutes(intent.minutes)
                    updateState { it.copy(workDayStartMinutes = intent.minutes) }
                }

                is SettingsIntent.WorkSchedule.UpdateWorkDayEnd -> {
                    settings.setWorkDayEndMinutes(intent.minutes)
                    updateState { it.copy(workDayEndMinutes = intent.minutes) }
                }

                is SettingsIntent.WorkSchedule.UpdateWorkLunchStart -> {
                    settings.setWorkLunchStartMinutes(intent.minutes)
                    updateState { it.copy(workLunchStartMinutes = intent.minutes) }
                }

                is SettingsIntent.WorkSchedule.UpdateWorkLunchEnd -> {
                    settings.setWorkLunchEndMinutes(intent.minutes)
                    updateState { it.copy(workLunchEndMinutes = intent.minutes) }
                }

                is SettingsIntent.WorkSchedule.UpdateWeekendSat -> {
                    settings.setWorkWeekendSat(intent.value)
                    updateState { it.copy(workWeekendSat = intent.value) }
                }

                is SettingsIntent.WorkSchedule.UpdateWeekendSun -> {
                    settings.setWorkWeekendSun(intent.value)
                    updateState { it.copy(workWeekendSun = intent.value) }
                }

                is SettingsIntent.Greeting.UpdateMorningEnd -> {
                    settings.setGreetingMorningEnd(intent.hour)
                    updateState { it.copy(greetingMorningEnd = intent.hour) }
                }

                is SettingsIntent.Greeting.UpdateAfternoonEnd -> {
                    settings.setGreetingAfternoonEnd(intent.hour)
                    updateState { it.copy(greetingAfternoonEnd = intent.hour) }
                }

                is SettingsIntent.Ai.UpdateProvider,
                is SettingsIntent.Ai.UpdateBaseUrl,
                is SettingsIntent.Ai.UpdateModel,
                is SettingsIntent.Ai.UpdateSystemPrompt,
                -> {
                    @Suppress("UNCHECKED_CAST")
                    (aiContributor as? SettingsContributor<SettingsSection.Ai, SettingsIntent.Ai>)
                        ?.apply(intent)
                    reloadAiSection()
                }

                SettingsIntent.Ai.TestConnection,
                SettingsIntent.Ai.FetchModels,
                -> {
                    @Suppress("UNCHECKED_CAST")
                    (aiContributor as? SettingsContributor<SettingsSection.Ai, SettingsIntent.Ai>)
                        ?.apply(intent as SettingsIntent.Ai)
                    reloadAiSection()
                }

                is SettingsIntent.Ai.UpdateApiKey -> {
                    aiContributor?.updateApiKey(intent.value)
                    reloadAiSection()
                }
            }
        }
    }

    private fun updateState(update: (SettingsUiState.Content) -> SettingsUiState.Content) {
        val current = _state.value as? SettingsUiState.Content ?: return
        _state.value = update(current)
    }

    private suspend fun reloadAiSection() {
        val contributor = aiContributor ?: return
        val provider = settings.aiProvider.first()
        val baseUrl = settings.aiBaseUrl.first()
        val model = settings.aiModel.first()
        val systemPrompt = settings.aiSystemPrompt.first()
        updateState {
            it.copy(
                aiProvider = provider,
                aiBaseUrl = baseUrl,
                aiModel = model,
                aiSystemPrompt = systemPrompt,
                aiTestResult = contributor.testResultStateFlow.value,
                aiModels = contributor.modelsStateFlow.value,
                isFetchingAiModels = contributor.isFetchingModelsStateFlow.value,
                fetchAiModelsError = contributor.fetchModelsErrorStateFlow.value,
            )
        }
    }

    fun updateAiApiKey(value: String) {
        scope.launch {
            aiContributor?.updateApiKey(value)
            reloadAiSection()
        }
    }
}
