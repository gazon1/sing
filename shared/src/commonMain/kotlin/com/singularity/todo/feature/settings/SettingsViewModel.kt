package com.singularity.todo.feature.settings

import androidx.lifecycle.ViewModel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.fireAndForget
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.core.ui.state.updateState
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.ai.AiSettingsContributor
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val contributors: Set<SettingsContributor<*, *>>,
    private val settings: SettingsRepository,
    private val scope: AutoCloseableCoroutineScope,
    private val savedAgendaViewsRepo: SavedAgendaViewsRepository,
    private val fileRevealer: FileRevealer,
) : ViewModel() {

    init {
        addCloseable(scope)
    }

    /** Production constructor — Koin uses this. */
    constructor(
        contributors: Set<SettingsContributor<*, *>>,
        settings: SettingsRepository,
        savedAgendaViewsRepo: SavedAgendaViewsRepository,
        fileRevealer: FileRevealer,
    ) : this(
        contributors = contributors,
        settings = settings,
        scope = AutoCloseableCoroutineScope(),
        savedAgendaViewsRepo = savedAgendaViewsRepo,
        fileRevealer = fileRevealer,
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
            savedAgendaViewsRepo.watchAllForCurrentUser().collect { views ->
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
        when (intent) {
            is SettingsIntent.Appearance.UpdateDarkTheme -> {
                updateState {
                    it.copy(
                        appearance = it.appearance.copy(darkTheme = intent.value),
                        darkTheme = intent.value,
                        errorMessage = null,
                    )
                }
                scope.fireAndForget(
                    errorLabel = "Update dark theme failed",
                    onError = { e -> updateState { it.copy(errorMessage = e.message ?: "Failed to update dark theme") } },
                ) { runCatching { settings.setDarkTheme(intent.value) } }
            }

            is SettingsIntent.Appearance.UpdateAccentColor -> {
                updateState {
                    it.copy(
                        appearance = it.appearance.copy(accentColor = intent.value),
                        accentColor = intent.value,
                        errorMessage = null,
                    )
                }
                scope.fireAndForget(
                    errorLabel = "Update accent color failed",
                    onError = { e -> updateState { it.copy(errorMessage = e.message ?: "Failed to update accent color") } },
                ) { runCatching { settings.setAccentColor(intent.value) } }
            }

            is SettingsIntent.Appearance.UpdateFontSizeScale -> {
                updateState {
                    it.copy(
                        appearance = it.appearance.copy(fontSizeScale = intent.value),
                        fontSizeScale = intent.value,
                        errorMessage = null,
                    )
                }
                scope.fireAndForget(
                    errorLabel = "Update font size failed",
                    onError = { e -> updateState { it.copy(errorMessage = e.message ?: "Failed to update font size") } },
                ) { runCatching { settings.setFontSizeScale(intent.value) } }
            }

            is SettingsIntent.Notifications.UpdateEnabled -> {
                updateState { it.copy(notificationsEnabled = intent.value, errorMessage = null) }
                scope.fireAndForget(
                    errorLabel = "Update notifications failed",
                    onError = { e -> updateState { it.copy(errorMessage = e.message ?: "Failed to update notifications") } },
                ) { runCatching { settings.setNotificationsEnabled(intent.value) } }
            }

            is SettingsIntent.Notifications.UpdateSound -> {
                updateState { it.copy(notificationSound = intent.value, errorMessage = null) }
                scope.fireAndForget(
                    errorLabel = "Update notification sound failed",
                    onError = { e -> updateState { it.copy(errorMessage = e.message ?: "Failed to update notification sound") } },
                ) { runCatching { settings.setNotificationSound(intent.value) } }
            }

            is SettingsIntent.Notifications.UpdateVibration -> {
                updateState { it.copy(notificationVibration = intent.value, errorMessage = null) }
                scope.fireAndForget(
                    errorLabel = "Update vibration failed",
                    onError = { e -> updateState { it.copy(errorMessage = e.message ?: "Failed to update vibration") } },
                ) { runCatching { settings.setNotificationVibration(intent.value) } }
            }

            is SettingsIntent.Notifications.UpdateReminderDefault -> {
                updateState { it.copy(reminderDefault = intent.value, errorMessage = null) }
                scope.fireAndForget(
                    errorLabel = "Update reminder default failed",
                    onError = { e -> updateState { it.copy(errorMessage = e.message ?: "Failed to update reminder default") } },
                ) { runCatching { settings.setReminderDefault(intent.value) } }
            }

            is SettingsIntent.WorkSchedule.UpdateWorkDayStart -> {
                updateState { it.copy(workDayStartMinutes = intent.minutes, errorMessage = null) }
                scope.fireAndForget(
                    errorLabel = "Update work day start failed",
                    onError = { e -> updateState { it.copy(errorMessage = e.message ?: "Failed to update work day start") } },
                ) { runCatching { settings.setWorkDayStartMinutes(intent.minutes) } }
            }

            is SettingsIntent.WorkSchedule.UpdateWorkDayEnd -> {
                updateState { it.copy(workDayEndMinutes = intent.minutes, errorMessage = null) }
                scope.fireAndForget(
                    errorLabel = "Update work day end failed",
                    onError = { e -> updateState { it.copy(errorMessage = e.message ?: "Failed to update work day end") } },
                ) { runCatching { settings.setWorkDayEndMinutes(intent.minutes) } }
            }

            is SettingsIntent.WorkSchedule.UpdateWorkLunchStart -> {
                updateState { it.copy(workLunchStartMinutes = intent.minutes, errorMessage = null) }
                scope.fireAndForget(
                    errorLabel = "Update lunch start failed",
                    onError = { e -> updateState { it.copy(errorMessage = e.message ?: "Failed to update lunch start") } },
                ) { runCatching { settings.setWorkLunchStartMinutes(intent.minutes) } }
            }

            is SettingsIntent.WorkSchedule.UpdateWorkLunchEnd -> {
                updateState { it.copy(workLunchEndMinutes = intent.minutes, errorMessage = null) }
                scope.fireAndForget(
                    errorLabel = "Update lunch end failed",
                    onError = { e -> updateState { it.copy(errorMessage = e.message ?: "Failed to update lunch end") } },
                ) { runCatching { settings.setWorkLunchEndMinutes(intent.minutes) } }
            }

            is SettingsIntent.WorkSchedule.UpdateWeekendSat -> {
                updateState { it.copy(workWeekendSat = intent.value, errorMessage = null) }
                scope.fireAndForget(
                    errorLabel = "Update Saturday setting failed",
                    onError = { e -> updateState { it.copy(errorMessage = e.message ?: "Failed to update Saturday setting") } },
                ) { runCatching { settings.setWorkWeekendSat(intent.value) } }
            }

            is SettingsIntent.WorkSchedule.UpdateWeekendSun -> {
                updateState { it.copy(workWeekendSun = intent.value, errorMessage = null) }
                scope.fireAndForget(
                    errorLabel = "Update Sunday setting failed",
                    onError = { e -> updateState { it.copy(errorMessage = e.message ?: "Failed to update Sunday setting") } },
                ) { runCatching { settings.setWorkWeekendSun(intent.value) } }
            }

            is SettingsIntent.Greeting.UpdateMorningEnd -> {
                updateState { it.copy(greetingMorningEnd = intent.hour, errorMessage = null) }
                scope.fireAndForget(
                    errorLabel = "Update morning greeting end failed",
                    onError = { e -> updateState { it.copy(errorMessage = e.message ?: "Failed to update morning greeting end") } },
                ) { runCatching { settings.setGreetingMorningEnd(intent.hour) } }
            }

            is SettingsIntent.Greeting.UpdateAfternoonEnd -> {
                updateState { it.copy(greetingAfternoonEnd = intent.hour, errorMessage = null) }
                scope.fireAndForget(
                    errorLabel = "Update afternoon greeting end failed",
                    onError = { e -> updateState { it.copy(errorMessage = e.message ?: "Failed to update afternoon greeting end") } },
                ) { runCatching { settings.setGreetingAfternoonEnd(intent.hour) } }
            }

            is SettingsIntent.Ai.UpdateProvider,
            is SettingsIntent.Ai.UpdateBaseUrl,
            is SettingsIntent.Ai.UpdateModel,
            is SettingsIntent.Ai.UpdateSystemPrompt,
            -> {
                scope.launch {
                    @Suppress("UNCHECKED_CAST")
                    (aiContributor as? SettingsContributor<SettingsSection.Ai, SettingsIntent.Ai>)
                        ?.process(intent)
                    reloadAiSection()
                }
            }

            SettingsIntent.Ai.TestConnection,
            SettingsIntent.Ai.FetchModels,
            -> {
                scope.launch {
                    @Suppress("UNCHECKED_CAST")
                    (aiContributor as? SettingsContributor<SettingsSection.Ai, SettingsIntent.Ai>)
                        ?.process(intent as SettingsIntent.Ai)
                    reloadAiSection()
                }
            }

            is SettingsIntent.Ai.UpdateApiKey -> {
                scope.launch {
                    aiContributor?.updateApiKey(intent.value)
                    reloadAiSection()
                }
            }

            is SettingsIntent.DefaultAgendaView.Update -> {
                updateState { it.copy(defaultSavedAgendaViewId = intent.viewId, errorMessage = null) }
                scope.fireAndForget(
                    errorLabel = "Update default agenda view failed",
                    onError = { e -> updateState { it.copy(errorMessage = e.message ?: "Failed to update default agenda view") } },
                ) { runCatching { settings.setDefaultSavedAgendaViewId(intent.viewId) } }
            }

            SettingsIntent.DismissError -> {
                updateState { it.copy(errorMessage = null) }
            }

            SettingsIntent.OpenAttachmentsFolder -> {
                scope.launch {
                    fileRevealer.revealAttachmentsFolder(fileRevealer.attachmentsBasePath())
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
