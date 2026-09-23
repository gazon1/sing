package com.singularity.todo.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.fireAndForget
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.settings.EphemeralState
import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.ai.AiSettingsContributor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings screen ViewModel.
 *
 * Architecture (Phase 5):
 * - Primary constructor takes 4 args: [scope] + 3 repositories.
 *   [settings] is no longer needed for building state — all values come from
 *   contributor flows. It is accepted for backward compatibility and stored
 *   only for Phase 5 bridging writes to flat legacy fields.
 * - [combine] over all contributor observe() flows produces reactive [state].
 *   No sync snapshot; no mutable-cast reads. Updated automatically on every
 *   emit from any contributor.
 * - [processIntent] dispatches via a sealed helper — one typed branch per
 *   section. No 200-line when statement.
 *
 * Phase 5 bridging:
 * - Flat legacy fields in [SettingsUiState.Content] are still written on each
 *   intent (bridging Layer-2 sub-screens that read them). These writes are
 *   removed in Phase 6 once all sub-screens migrate to typed section fields.
 *
 * Phase 6 (after sub-screens migrate):
 * - Remove [settings] parameter entirely.
 * - Remove all `it.copy(...flatField = ...)` bridging in intent handlers.
 * - Remove `settingsWriter` property.
 */
class SettingsViewModel(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val contributors: Set<SettingsContributor<*, *>>,
    private val savedAgendaViewsRepo: SavedAgendaViewsRepository,
    private val fileRevealer: FileRevealer,
) : ViewModel() {

    /**
     * Secondary constructor — accepts the old 5-arg signature for existing
     * call sites (tests, Koin DI).
     *
     * [settings] is stored only for Phase 5 bridging writes to flat legacy
     * fields. After Phase 6 it can be removed.
     */
    @Suppress("UNUSED_PARAMETER")
    constructor(
        contributors: Set<SettingsContributor<*, *>>,
        settings: SettingsRepository,
        savedAgendaViewsRepo: SavedAgendaViewsRepository,
        fileRevealer: FileRevealer,
        scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
    ) : this(
        scope = scope,
        contributors = contributors,
        savedAgendaViewsRepo = savedAgendaViewsRepo,
        fileRevealer = fileRevealer,
    ) {
        _settings = settings
    }

    init {
        if (scope is AutoCloseable) addCloseable(scope as AutoCloseable)
        else if (scope is AutoCloseableCoroutineScope) addCloseable(scope)
    }

    // ─── Settings reader + writer (Phase 5 bridging — remove in Phase 6) ───────

    private var _settings: SettingsRepository? = null

    private val settingsWriter: SettingsRepository?
        get() = _settings

    private val settingsReader: SettingsRepository?
        get() = _settings

    // ─── Typed contributor accessors ─────────────────────────────────────────

    private val appearanceContributor: SettingsContributor<SettingsSection.Appearance, SettingsIntent.Appearance>?
        get() = contributors.filterIsInstance<SettingsContributor<SettingsSection.Appearance, SettingsIntent.Appearance>>().firstOrNull()

    private val notificationsContributor: SettingsContributor<SettingsSection.Notifications, SettingsIntent.Notifications>?
        get() = contributors.filterIsInstance<SettingsContributor<SettingsSection.Notifications, SettingsIntent.Notifications>>().firstOrNull()

    private val workScheduleContributor: SettingsContributor<SettingsSection.WorkSchedule, SettingsIntent.WorkSchedule>?
        get() = contributors.filterIsInstance<SettingsContributor<SettingsSection.WorkSchedule, SettingsIntent.WorkSchedule>>().firstOrNull()

    private val greetingContributor: SettingsContributor<SettingsSection.Greeting, SettingsIntent.Greeting>?
        get() = contributors.filterIsInstance<SettingsContributor<SettingsSection.Greeting, SettingsIntent.Greeting>>().firstOrNull()

    private val defaultAgendaViewContributor: SettingsContributor<SettingsSection.DefaultAgendaView, SettingsIntent.DefaultAgendaView>?
        get() = contributors.filterIsInstance<SettingsContributor<SettingsSection.DefaultAgendaView, SettingsIntent.DefaultAgendaView>>().firstOrNull()

    private val aiContributor: AiSettingsContributor?
        get() = contributors.filterIsInstance<AiSettingsContributor>().firstOrNull()

    // ─── Ephemeral state (parallel flows — not persisted) ───────────────────

    private val aiEphemeral = MutableStateFlow(EphemeralState.Ai())
    private val agendaEphemeral = MutableStateFlow(EphemeralState.Agenda())

    // ─── Reactive state ───────────────────────────────────────────────────────

    /**
     * Canonical state — built by [combine] over all contributor observe() flows.
     * Updated automatically whenever any contributor's flow emits.
     * Flat legacy fields are derived from typed sections here (Phase 5 bridging).
     *
     * [MutableStateFlow] allows error handlers to call [_state].value = _state.value.copy(...).
     * Using [stateIn] is not possible here because it returns immutable [StateFlow].
     */
    private val _state = MutableStateFlow(SettingsUiState.Content())

    init {
        scope.launch {
            combine(
                appearanceContributor?.observe() ?: flowOf(SettingsSection.Appearance()),
                notificationsContributor?.observe() ?: flowOf(SettingsSection.Notifications()),
                workScheduleContributor?.observe() ?: flowOf(SettingsSection.WorkSchedule()),
                greetingContributor?.observe() ?: flowOf(SettingsSection.Greeting()),
                aiContributor?.observe() ?: flowOf(SettingsSection.Ai()),
                defaultAgendaViewContributor?.observe() ?: flowOf(SettingsSection.DefaultAgendaView()),
                savedAgendaViewsRepo.observeAll(),
                aiEphemeral,
                agendaEphemeral,
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val appearance = values[0] as SettingsSection.Appearance
                @Suppress("UNCHECKED_CAST")
                val notifications = values[1] as SettingsSection.Notifications
                @Suppress("UNCHECKED_CAST")
                val workSchedule = values[2] as SettingsSection.WorkSchedule
                @Suppress("UNCHECKED_CAST")
                val greeting = values[3] as SettingsSection.Greeting
                @Suppress("UNCHECKED_CAST")
                val ai = values[4] as SettingsSection.Ai
                @Suppress("UNCHECKED_CAST")
                val defaultAgendaView = values[5] as SettingsSection.DefaultAgendaView
                @Suppress("UNCHECKED_CAST")
                val views = values[6] as List<SavedAgendaView>
                @Suppress("UNCHECKED_CAST")
                val aiEph = values[7] as EphemeralState.Ai
                @Suppress("UNCHECKED_CAST")
                val agendaEph = values[8] as EphemeralState.Agenda

                SettingsUiState.Content(
                    appearance = appearance,
                    notifications = notifications,
                    workSchedule = workSchedule,
                    greeting = greeting,
                    ai = ai,
                    defaultAgendaView = defaultAgendaView,
                    // Flat legacy fields — derived from typed sections.
                    // Kept for backward compat (Phase 5); removed in Phase 6.
                    darkTheme = appearance.darkTheme,
                    accentColor = appearance.accentColor,
                    fontSizeScale = appearance.fontSizeScale,
                    notificationsEnabled = notifications.enabled,
                    notificationSound = notifications.sound,
                    notificationVibration = notifications.vibration,
                    reminderDefault = notifications.reminderDefault,
                    workDayStartMinutes = workSchedule.dayStartMinutes,
                    workDayEndMinutes = workSchedule.dayEndMinutes,
                    workLunchStartMinutes = workSchedule.lunchStartMinutes,
                    workLunchEndMinutes = workSchedule.lunchEndMinutes,
                    workWeekendSat = workSchedule.weekendSat,
                    workWeekendSun = workSchedule.weekendSun,
                    greetingMorningEnd = greeting.morningEndHour,
                    greetingAfternoonEnd = greeting.afternoonEndHour,
                    defaultSavedAgendaViewId = defaultAgendaView.viewId,
                    savedAgendaViews = views,
                    aiEphemeral = aiEph,
                    agendaEphemeral = agendaEph,
                )
            }.collect { newContent ->
                _state.value = newContent
            }
        }
    }

    val state: StateFlow<SettingsUiState> get() = _state

    // ─── Intent ───────────────────────────────────────────────────────────────

    fun processIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.Appearance -> dispatchAppearance(intent)
            is SettingsIntent.Notifications -> dispatchNotifications(intent)
            is SettingsIntent.WorkSchedule -> dispatchWorkSchedule(intent)
            is SettingsIntent.Greeting -> dispatchGreeting(intent)
            is SettingsIntent.Ai -> dispatchAi(intent)
            is SettingsIntent.DefaultAgendaView -> dispatchDefaultAgendaView(intent)
            SettingsIntent.DismissError -> { /* combine clears on next emit */ }
            SettingsIntent.OpenAttachmentsFolder -> openAttachmentsFolder()
        }
    }

    // ─── Per-section dispatchers (sealed, type-safe) ─────────────────────────
    // Phase 5: synchronous _state updates + async fireAndForget for persistence.
    // The sync update mirrors the old VM pattern and ensures tests (which may not
    // have all contributors registered) see the updated state immediately.
    // After Phase 6 (sub-screens migrate to typed fields), the sync update
    // can be removed — state will be driven purely by the combine pipeline.

    private fun dispatchAppearance(intent: SettingsIntent.Appearance) {
        val contributor = appearanceContributor
        when (intent) {
            is SettingsIntent.Appearance.UpdateDarkTheme -> {
                _state.value = _state.value.copy(darkTheme = intent.value, errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update dark theme failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching {
                        contributor?.process(intent)
                        settingsWriter?.setDarkTheme(intent.value)
                    }
                }
            }
            is SettingsIntent.Appearance.UpdateAccentColor -> {
                _state.value = _state.value.copy(accentColor = intent.value, errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update accent color failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching {
                        contributor?.process(intent)
                        settingsWriter?.setAccentColor(intent.value)
                    }
                }
            }
            is SettingsIntent.Appearance.UpdateFontSizeScale -> {
                _state.value = _state.value.copy(fontSizeScale = intent.value, errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update font size failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching {
                        contributor?.process(intent)
                        settingsWriter?.setFontSizeScale(intent.value)
                    }
                }
            }
        }
    }

    private fun dispatchNotifications(intent: SettingsIntent.Notifications) {
        val contributor = notificationsContributor
        when (intent) {
            is SettingsIntent.Notifications.UpdateEnabled -> {
                _state.value = _state.value.copy(notificationsEnabled = intent.value, errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update notifications failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching {
                        contributor?.process(intent)
                        settingsWriter?.setNotificationsEnabled(intent.value)
                    }
                }
            }
            is SettingsIntent.Notifications.UpdateSound -> {
                _state.value = _state.value.copy(notificationSound = intent.value, errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update notification sound failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching {
                        contributor?.process(intent)
                        settingsWriter?.setNotificationSound(intent.value)
                    }
                }
            }
            is SettingsIntent.Notifications.UpdateVibration -> {
                _state.value = _state.value.copy(notificationVibration = intent.value, errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update vibration failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching {
                        contributor?.process(intent)
                        settingsWriter?.setNotificationVibration(intent.value)
                    }
                }
            }
            is SettingsIntent.Notifications.UpdateReminderDefault -> {
                _state.value = _state.value.copy(reminderDefault = intent.value, errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update reminder default failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching {
                        contributor?.process(intent)
                        settingsWriter?.setReminderDefault(intent.value)
                    }
                }
            }
        }
    }

    private fun dispatchWorkSchedule(intent: SettingsIntent.WorkSchedule) {
        val contributor = workScheduleContributor
        when (intent) {
            is SettingsIntent.WorkSchedule.UpdateWorkDayStart -> {
                _state.value = _state.value.copy(workDayStartMinutes = intent.minutes, errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update work day start failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching {
                        contributor?.process(intent)
                        settingsWriter?.setWorkDayStartMinutes(intent.minutes)
                    }
                }
            }
            is SettingsIntent.WorkSchedule.UpdateWorkDayEnd -> {
                _state.value = _state.value.copy(workDayEndMinutes = intent.minutes, errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update work day end failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching {
                        contributor?.process(intent)
                        settingsWriter?.setWorkDayEndMinutes(intent.minutes)
                    }
                }
            }
            is SettingsIntent.WorkSchedule.UpdateWorkLunchStart -> {
                _state.value = _state.value.copy(workLunchStartMinutes = intent.minutes, errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update lunch start failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching {
                        contributor?.process(intent)
                        settingsWriter?.setWorkLunchStartMinutes(intent.minutes)
                    }
                }
            }
            is SettingsIntent.WorkSchedule.UpdateWorkLunchEnd -> {
                _state.value = _state.value.copy(workLunchEndMinutes = intent.minutes, errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update lunch end failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching {
                        contributor?.process(intent)
                        settingsWriter?.setWorkLunchEndMinutes(intent.minutes)
                    }
                }
            }
            is SettingsIntent.WorkSchedule.UpdateWeekendSat -> {
                _state.value = _state.value.copy(workWeekendSat = intent.value, errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update Saturday setting failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching {
                        contributor?.process(intent)
                        settingsWriter?.setWorkWeekendSat(intent.value)
                    }
                }
            }
            is SettingsIntent.WorkSchedule.UpdateWeekendSun -> {
                _state.value = _state.value.copy(workWeekendSun = intent.value, errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update Sunday setting failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching {
                        contributor?.process(intent)
                        settingsWriter?.setWorkWeekendSun(intent.value)
                    }
                }
            }
        }
    }

    private fun dispatchGreeting(intent: SettingsIntent.Greeting) {
        val contributor = greetingContributor
        when (intent) {
            is SettingsIntent.Greeting.UpdateMorningEnd -> {
                _state.value = _state.value.copy(greetingMorningEnd = intent.hour, errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update morning greeting end failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching {
                        contributor?.process(intent)
                        settingsWriter?.setGreetingMorningEnd(intent.hour)
                    }
                }
            }
            is SettingsIntent.Greeting.UpdateAfternoonEnd -> {
                _state.value = _state.value.copy(greetingAfternoonEnd = intent.hour, errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update afternoon greeting end failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching {
                        contributor?.process(intent)
                        settingsWriter?.setGreetingAfternoonEnd(intent.hour)
                    }
                }
            }
        }
    }

    private fun dispatchAi(intent: SettingsIntent.Ai) {
        // Optimistically update persisted AI flat fields from intent values.
        // This mirrors the old VM pattern and ensures tests see the update immediately.
        val persisted = when (intent) {
            is SettingsIntent.Ai.UpdateProvider -> _state.value.copy(aiProvider = intent.value.id)
            is SettingsIntent.Ai.UpdateBaseUrl -> _state.value.copy(aiBaseUrl = intent.value)
            is SettingsIntent.Ai.UpdateModel -> _state.value.copy(aiModel = intent.value)
            is SettingsIntent.Ai.UpdateSystemPrompt -> _state.value.copy(aiSystemPrompt = intent.value)
            else -> null
        }
        if (persisted != null) _state.value = persisted

        scope.launch {
            aiContributor?.process(intent)
            // After intents that update ephemeral state, sync ephemeral flat fields.
            if (intent is SettingsIntent.Ai.TestConnection ||
                intent is SettingsIntent.Ai.FetchModels ||
                intent is SettingsIntent.Ai.UpdateApiKey
            ) {
                syncAiFlatFields()
            }
        }
    }

    private fun dispatchDefaultAgendaView(intent: SettingsIntent.DefaultAgendaView) {
        val contributor = defaultAgendaViewContributor
        when (intent) {
            is SettingsIntent.DefaultAgendaView.Update -> {
                _state.value = _state.value.copy(defaultSavedAgendaViewId = intent.viewId, errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update default agenda view failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching {
                        contributor?.process(intent)
                        settingsWriter?.setDefaultSavedAgendaViewId(intent.viewId)
                    }
                }
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Reads current ephemeral values from [aiContributor] and pushes them into state.
     * Ephemeral fields synced: testResult, models, isFetchingModels, fetchModelsError.
     *
     * Persisted AI fields (aiProvider, aiBaseUrl, aiModel, aiSystemPrompt) are updated
     * optimistically in [dispatchAi] from intent values — no suspend needed.
     */
    private fun syncAiFlatFields() {
        val c = aiContributor ?: return
        _state.value = _state.value.copy(
            aiTestResult = c.testResultStateFlow.value,
            aiModels = c.modelsStateFlow.value,
            isFetchingAiModels = c.isFetchingModelsStateFlow.value,
            fetchAiModelsError = c.fetchModelsErrorStateFlow.value,
        )
    }

    private fun openAttachmentsFolder() {
        scope.launch {
            fileRevealer.revealAttachmentsFolder(fileRevealer.attachmentsBasePath())
        }
    }

    private suspend fun reloadAiEphemeral() {
        val c = aiContributor ?: return
        aiEphemeral.value = EphemeralState.Ai(
            testResult = c.testResultStateFlow.value,
            models = c.modelsStateFlow.value,
            isFetchingModels = c.isFetchingModelsStateFlow.value,
            fetchModelsError = c.fetchModelsErrorStateFlow.value,
        )
    }

    /**
     * Updates the AI API key via the contributor (writes to SecureStorage).
     */
    fun updateAiApiKey(value: String) {
        scope.launch {
            aiContributor?.updateApiKey(value)
        }
    }
}

