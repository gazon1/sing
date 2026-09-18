package com.singularity.todo.feature.settings

import androidx.lifecycle.ViewModel

import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.ai.AiSettingsContributor
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * Settings screen ViewModel.
 *
 * Простой подход:
 * - UI state = combine всех contributors + settings flows
 * - Нет reactive snapshot — просто читаем .value синхронно
 * - processIntent обновляет state напрямую после записи в repository
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val contributors: Set<SettingsContributor<*, *>>,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val savedAgendaViewsRepo: SavedAgendaViewsRepository,
    private val currentUser: ProfileAwareCurrentUser,
) : ViewModel() {

    /** Production constructor — Koin uses this. */
    constructor(
        contributors: Set<SettingsContributor<*, *>>,
        settings: SettingsRepository,
        savedAgendaViewsRepo: SavedAgendaViewsRepository,
        currentUser: ProfileAwareCurrentUser,
    ) : this(
        contributors = contributors,
        settings = settings,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        savedAgendaViewsRepo = savedAgendaViewsRepo,
        currentUser = currentUser,
    )

    private val aiContributor: AiSettingsContributor?
        @Suppress("DEPRECATION")
        get() =
            contributors.filterIsInstance(AiSettingsContributor::class.java).firstOrNull()

    // ─── State ─────────────────────────────────────────────────────────────

    // Initialized before _state so that buildState() can reference it.
    // Starts empty; init block replaces it once the flow starts emitting.
    private val savedAgendaViews = MutableStateFlow<List<SavedAgendaView>>(emptyList())

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<SettingsUiState> get() = _state

    // ─── Init ─────────────────────────────────────────────────────────────

    init {
        scope.launch {
            _state.value = buildState()
        }
        scope.launch {
            currentUser.userId.flatMapLatest { userId ->
                savedAgendaViewsRepo.watchAll(userId.value)
            }.collect { views ->
                savedAgendaViews.value = views
            }
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
            defaultSavedAgendaViewId = settings.defaultSavedAgendaViewId.let { (it as? MutableStateFlow)?.value },
            savedAgendaViews = savedAgendaViews.value,
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
                            appearance = it.appearance.copy(darkTheme = with(intent) { value }),
                            darkTheme = with(intent) { value },
                        )
                    }
                }

                is SettingsIntent.Appearance.UpdateAccentColor -> {
                    settings.setAccentColor(intent.value)
                    updateState {
                        it.copy(
                            appearance = it.appearance.copy(accentColor = with(intent) { value }),
                            accentColor = with(intent) { value },
                        )
                    }
                }

                is SettingsIntent.Appearance.UpdateFontSizeScale -> {
                    settings.setFontSizeScale(intent.value)
                    updateState {
                        it.copy(
                            appearance = it.appearance.copy(fontSizeScale = with(intent) { value }),
                            fontSizeScale = with(intent) { value },
                        )
                    }
                }

                is SettingsIntent.Notifications.UpdateEnabled -> {
                    settings.setNotificationsEnabled(intent.value)
                    updateState { it.copy(notificationsEnabled = with(intent) { value }) }
                }

                is SettingsIntent.Notifications.UpdateSound -> {
                    settings.setNotificationSound(intent.value)
                    updateState { it.copy(notificationSound = with(intent) { value }) }
                }

                is SettingsIntent.Notifications.UpdateVibration -> {
                    settings.setNotificationVibration(intent.value)
                    updateState { it.copy(notificationVibration = with(intent) { value }) }
                }

                is SettingsIntent.Notifications.UpdateReminderDefault -> {
                    settings.setReminderDefault(intent.value)
                    updateState { it.copy(reminderDefault = with(intent) { value }) }
                }

                is SettingsIntent.WorkSchedule.UpdateWorkDayStart -> {
                    settings.setWorkDayStartMinutes(intent.minutes)
                    updateState { it.copy(workDayStartMinutes = with(intent) { minutes }) }
                }

                is SettingsIntent.WorkSchedule.UpdateWorkDayEnd -> {
                    settings.setWorkDayEndMinutes(intent.minutes)
                    updateState { it.copy(workDayEndMinutes = with(intent) { minutes }) }
                }

                is SettingsIntent.WorkSchedule.UpdateWorkLunchStart -> {
                    settings.setWorkLunchStartMinutes(intent.minutes)
                    updateState { it.copy(workLunchStartMinutes = with(intent) { minutes }) }
                }

                is SettingsIntent.WorkSchedule.UpdateWorkLunchEnd -> {
                    settings.setWorkLunchEndMinutes(intent.minutes)
                    updateState { it.copy(workLunchEndMinutes = with(intent) { minutes }) }
                }

                is SettingsIntent.WorkSchedule.UpdateWeekendSat -> {
                    settings.setWorkWeekendSat(intent.value)
                    updateState { it.copy(workWeekendSat = with(intent) { value }) }
                }

                is SettingsIntent.WorkSchedule.UpdateWeekendSun -> {
                    settings.setWorkWeekendSun(intent.value)
                    updateState { it.copy(workWeekendSun = with(intent) { value }) }
                }

                is SettingsIntent.Greeting.UpdateMorningEnd -> {
                    settings.setGreetingMorningEnd(intent.hour)
                    updateState { it.copy(greetingMorningEnd = with(intent) { hour }) }
                }

                is SettingsIntent.Greeting.UpdateAfternoonEnd -> {
                    settings.setGreetingAfternoonEnd(intent.hour)
                    updateState { it.copy(greetingAfternoonEnd = with(intent) { hour }) }
                }

                is SettingsIntent.Ai.UpdateProvider,
                is SettingsIntent.Ai.UpdateBaseUrl,
                is SettingsIntent.Ai.UpdateModel,
                is SettingsIntent.Ai.UpdateSystemPrompt,
                -> {
                    @Suppress("UNCHECKED_CAST")
                    (aiContributor as? SettingsContributor<SettingsSection.Ai, SettingsIntent.Ai>)
                        ?.process(intent)
                    reloadAiSection()
                }

                SettingsIntent.Ai.TestConnection,
                SettingsIntent.Ai.FetchModels,
                -> {
                    @Suppress("UNCHECKED_CAST")
                    (aiContributor as? SettingsContributor<SettingsSection.Ai, SettingsIntent.Ai>)
                        ?.process(intent as SettingsIntent.Ai)
                    reloadAiSection()
                }

                is SettingsIntent.Ai.UpdateApiKey -> {
                    aiContributor?.updateApiKey(intent.value)
                    reloadAiSection()
                }

                is SettingsIntent.DefaultAgendaView.Update -> {
                    settings.setDefaultSavedAgendaViewId(intent.viewId)
                    updateState { it.copy(defaultSavedAgendaViewId = with(intent) { viewId }) }
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
