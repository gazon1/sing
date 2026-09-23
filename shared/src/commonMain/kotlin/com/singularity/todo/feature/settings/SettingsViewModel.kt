package com.singularity.todo.feature.settings

import androidx.lifecycle.ViewModel
import com.singularity.todo.core.appearance.AppearanceContributor
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.fireAndForget
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.notifications.NotificationsContributor
import com.singularity.todo.core.schedule.GreetingContributor
import com.singularity.todo.core.schedule.WorkScheduleContributor
import com.singularity.todo.core.settings.EphemeralState
import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.feature.agenda.DefaultAgendaViewContributor
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.ai.AiContributor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Settings screen ViewModel.
 *
 * Architecture:
 * - Primary constructor takes [AutoCloseableCoroutineScope] + individual contributor
 *   instances (injected via `filterIsInstance<Contributor>()` in Koin DI).
 * - Marker interfaces ([AppearanceContributor], [AiContributor], etc.) enable
 *   compile-safe lookup without Kotlin type erasure.
 * - Each contributor's [observe] feeds a dedicated [MutableStateFlow] independently.
 * - [combine] merges per-section flows + ephemeral state into [Content].
 * - [processIntent] dispatches via typed helpers with [fireAndForget] error handling.
 */
class SettingsViewModel(
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
    private val appearanceContributor: AppearanceContributor?,
    private val notificationsContributor: NotificationsContributor?,
    private val workScheduleContributor: WorkScheduleContributor?,
    private val greetingContributor: GreetingContributor?,
    private val aiContributor: AiContributor?,
    private val defaultAgendaViewContributor: DefaultAgendaViewContributor?,
    private val savedAgendaViewsRepo: SavedAgendaViewsRepository,
    private val fileRevealer: FileRevealer,
) : ViewModel() {

    init {
        addCloseable(scope)
    }

    // ─── Per-section state flows ──────────────────────────────────────────────
    // Each section feeds its own MutableStateFlow so combine doesn't block on
    // missing contributors. Each contributor's observe() is collected once.

    private val appearanceFlow = MutableStateFlow(SettingsSection.Appearance())
    private val notificationsFlow = MutableStateFlow(SettingsSection.Notifications())
    private val workScheduleFlow = MutableStateFlow(SettingsSection.WorkSchedule())
    private val greetingFlow = MutableStateFlow(SettingsSection.Greeting())
    private val aiFlow = MutableStateFlow(SettingsSection.Ai())
    private val defaultAgendaViewFlow = MutableStateFlow(SettingsSection.DefaultAgendaView())

    // ─── Ephemeral state ────────────────────────────────────────────────────

    private val aiEphemeral = MutableStateFlow(EphemeralState.Ai())
    private val agendaEphemeral = MutableStateFlow(EphemeralState.Agenda())

    // ─── UI state ────────────────────────────────────────────────────────────

    private val _state = MutableStateFlow(SettingsUiState.Content())
    val state: StateFlow<SettingsUiState> get() = _state

    init {
        // Seed state with all defaults immediately (before any flow emits).
        _state.value = SettingsUiState.Content(
            appearance = appearanceFlow.value,
            notifications = notificationsFlow.value,
            workSchedule = workScheduleFlow.value,
            greeting = greetingFlow.value,
            ai = aiFlow.value,
            defaultAgendaView = defaultAgendaViewFlow.value,
        )

        // Observe each contributor independently — each flow rebuilds _state on change.
        appearanceContributor?.observe()
            ?.onEach { section ->
                appearanceFlow.value = section
                rebuildState()
            }
            ?.launchIn(scope)

        notificationsContributor?.observe()
            ?.onEach { section ->
                notificationsFlow.value = section
                rebuildState()
            }
            ?.launchIn(scope)

        workScheduleContributor?.observe()
            ?.onEach { section ->
                workScheduleFlow.value = section
                rebuildState()
            }
            ?.launchIn(scope)

        greetingContributor?.observe()
            ?.onEach { section ->
                greetingFlow.value = section
                rebuildState()
            }
            ?.launchIn(scope)

        aiContributor?.observe()
            ?.onEach { section ->
                aiFlow.value = section
                rebuildState()
            }
            ?.launchIn(scope)

        defaultAgendaViewContributor?.observe()
            ?.onEach { section ->
                defaultAgendaViewFlow.value = section
                rebuildState()
            }
            ?.launchIn(scope)

        savedAgendaViewsRepo.observeAll()
            .onEach { views ->
                agendaEphemeral.value = agendaEphemeral.value.copy(savedViews = views)
                rebuildState()
            }
            .launchIn(scope)

        // Bridge AI ephemeral state from the single combined flow.
        aiContributor?.ephemeralStateFlow
            ?.onEach { aiEph ->
                aiEphemeral.value = aiEph
                _state.value = _state.value.copy(aiEphemeral = aiEph)
            }
            ?.launchIn(scope)
    }

    /** Rebuilds the full Content from current per-section flows. */
    private fun rebuildState() {
        _state.value = _state.value.copy(
            appearance = appearanceFlow.value,
            notifications = notificationsFlow.value,
            workSchedule = workScheduleFlow.value,
            greeting = greetingFlow.value,
            ai = aiFlow.value,
            defaultAgendaView = defaultAgendaViewFlow.value,
        )
    }

    // ─── Intent ───────────────────────────────────────────────────────────────

    fun processIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.Appearance -> dispatchAppearance(intent)
            is SettingsIntent.Notifications -> dispatchNotifications(intent)
            is SettingsIntent.WorkSchedule -> dispatchWorkSchedule(intent)
            is SettingsIntent.Greeting -> dispatchGreeting(intent)
            is SettingsIntent.Ai -> dispatchAi(intent)
            is SettingsIntent.DefaultAgendaView -> dispatchDefaultAgendaView(intent)
            SettingsIntent.DismissError -> { /* ephemeral; cleared on next emit */ }
            SettingsIntent.OpenAttachmentsFolder -> openAttachmentsFolder()
        }
    }

    // ─── Per-section dispatchers ──────────────────────────────────────────────

    private fun dispatchAppearance(intent: SettingsIntent.Appearance) {
        val contributor = appearanceContributor
        when (intent) {
            is SettingsIntent.Appearance.UpdateDarkTheme -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update dark theme failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) { runCatching { contributor?.process(intent) } }
            }
            is SettingsIntent.Appearance.UpdateAccentColor -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update accent color failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) { runCatching { contributor?.process(intent) } }
            }
            is SettingsIntent.Appearance.UpdateFontSizeScale -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update font size failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) { runCatching { contributor?.process(intent) } }
            }
        }
    }

    private fun dispatchNotifications(intent: SettingsIntent.Notifications) {
        val contributor = notificationsContributor
        when (intent) {
            is SettingsIntent.Notifications.UpdateEnabled -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update notifications failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) { runCatching { contributor?.process(intent) } }
            }
            is SettingsIntent.Notifications.UpdateSound -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update notification sound failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) { runCatching { contributor?.process(intent) } }
            }
            is SettingsIntent.Notifications.UpdateVibration -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update vibration failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) { runCatching { contributor?.process(intent) } }
            }
            is SettingsIntent.Notifications.UpdateReminderDefault -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update reminder default failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) { runCatching { contributor?.process(intent) } }
            }
        }
    }

    private fun dispatchWorkSchedule(intent: SettingsIntent.WorkSchedule) {
        val contributor = workScheduleContributor
        when (intent) {
            is SettingsIntent.WorkSchedule.UpdateWorkDayStart -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update work day start failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) { runCatching { contributor?.process(intent) } }
            }
            is SettingsIntent.WorkSchedule.UpdateWorkDayEnd -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update work day end failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) { runCatching { contributor?.process(intent) } }
            }
            is SettingsIntent.WorkSchedule.UpdateWorkLunchStart -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update lunch start failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) { runCatching { contributor?.process(intent) } }
            }
            is SettingsIntent.WorkSchedule.UpdateWorkLunchEnd -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update lunch end failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) { runCatching { contributor?.process(intent) } }
            }
            is SettingsIntent.WorkSchedule.UpdateWeekendSat -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update Saturday setting failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) { runCatching { contributor?.process(intent) } }
            }
            is SettingsIntent.WorkSchedule.UpdateWeekendSun -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update Sunday setting failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) { runCatching { contributor?.process(intent) } }
            }
        }
    }

    private fun dispatchGreeting(intent: SettingsIntent.Greeting) {
        val contributor = greetingContributor
        when (intent) {
            is SettingsIntent.Greeting.UpdateMorningEnd -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update morning greeting end failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) { runCatching { contributor?.process(intent) } }
            }
            is SettingsIntent.Greeting.UpdateAfternoonEnd -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update afternoon greeting end failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) { runCatching { contributor?.process(intent) } }
            }
        }
    }

    private fun dispatchAi(intent: SettingsIntent.Ai) {
        _state.value = _state.value.copy(errorMessage = null)
        scope.fireAndForget(
            errorLabel = "AI ${intent::class.simpleName} failed",
            onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
        ) { runCatching { aiContributor?.process(intent) } }
    }

    private fun dispatchDefaultAgendaView(intent: SettingsIntent.DefaultAgendaView) {
        val contributor = defaultAgendaViewContributor
        when (intent) {
            is SettingsIntent.DefaultAgendaView.Update -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update default agenda view failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) { runCatching { contributor?.process(intent) } }
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun openAttachmentsFolder() {
        scope.launch {
            fileRevealer.revealAttachmentsFolder(fileRevealer.attachmentsBasePath())
        }
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
