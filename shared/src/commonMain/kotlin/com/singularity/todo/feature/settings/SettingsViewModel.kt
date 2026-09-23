package com.singularity.todo.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singularity.todo.core.coroutines.fireAndForget
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.settings.EphemeralState
import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.ai.AiSettingsContributor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Settings screen ViewModel.
 *
 * Architecture (Phase 5, cleaned up in Phase 8):
 * - Primary constructor takes 4 args: [scope] + contributors + repositories.
 * - Each contributor's observe() feeds a dedicated [MutableStateFlow] via [launch].
 *   This decouples emission timing — each section updates independently, with no
 *   "all must emit before transform" gate.
 * - [combine] merges the 2 ephemeral flows + saved views into final [Content].
 * - [processIntent] dispatches via a sealed helper — one typed branch per section.
 * - Flat legacy fields are gone: state uses typed contributor sections directly.
 * - Ephemeral AI state lives in [aiEphemeral].
 */
class SettingsViewModel(
    private val scope: CoroutineScope,
    private val contributors: Set<SettingsContributor<*, *>>,
    private val savedAgendaViewsRepo: SavedAgendaViewsRepository,
    private val fileRevealer: FileRevealer,
) : ViewModel() {

    /**
     * Secondary constructor — accepts the old 5-arg signature for existing
     * call sites (tests, Koin DI).
     */
    @Suppress("UNUSED_PARAMETER")
    constructor(
        contributors: Set<SettingsContributor<*, *>>,
        settings: Any,
        savedAgendaViewsRepo: SavedAgendaViewsRepository,
        fileRevealer: FileRevealer,
        scope: com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope,
    ) : this(
        scope = scope as CoroutineScope,
        contributors = contributors,
        savedAgendaViewsRepo = savedAgendaViewsRepo,
        fileRevealer = fileRevealer,
    )

    init {
        val closeable = scope as? java.io.Closeable
        if (closeable != null) {
            @Suppress("DEPRECATION")
            addCloseable(closeable)
        }
    }

    // ─── Typed contributor accessors ─────────────────────────────────────────
    // Use simple class-name matching to avoid Kotlin generics type-erasure.
    // Kotlin doesn't preserve generic type parameters of implemented interfaces
    // at runtime (genericSuperclass is Object for all contributors), so we use
    // the stable class name of each concrete contributor subclass.

    private val appearanceContributor: SettingsContributor<SettingsSection.Appearance, SettingsIntent.Appearance>?
        get() = @Suppress("UNCHECKED_CAST") (contributors.find {
            it::class.java.simpleName == "AppearanceSettingsContributor"
        } as SettingsContributor<SettingsSection.Appearance, SettingsIntent.Appearance>?)

    private val notificationsContributor: SettingsContributor<SettingsSection.Notifications, SettingsIntent.Notifications>?
        get() = @Suppress("UNCHECKED_CAST") (contributors.find {
            it::class.java.simpleName == "NotificationsSettingsContributor"
        } as SettingsContributor<SettingsSection.Notifications, SettingsIntent.Notifications>?)

    private val workScheduleContributor: SettingsContributor<SettingsSection.WorkSchedule, SettingsIntent.WorkSchedule>?
        get() = @Suppress("UNCHECKED_CAST") (contributors.find {
            it::class.java.simpleName == "WorkScheduleSettingsContributor"
        } as SettingsContributor<SettingsSection.WorkSchedule, SettingsIntent.WorkSchedule>?)

    private val greetingContributor: SettingsContributor<SettingsSection.Greeting, SettingsIntent.Greeting>?
        get() = @Suppress("UNCHECKED_CAST") (contributors.find {
            it::class.java.simpleName == "GreetingSettingsContributor"
        } as SettingsContributor<SettingsSection.Greeting, SettingsIntent.Greeting>?)

    private val defaultAgendaViewContributor: SettingsContributor<SettingsSection.DefaultAgendaView, SettingsIntent.DefaultAgendaView>?
        get() = @Suppress("UNCHECKED_CAST") (contributors.find {
            it::class.java.simpleName == "DefaultAgendaViewSettingsContributor"
        } as SettingsContributor<SettingsSection.DefaultAgendaView, SettingsIntent.DefaultAgendaView>?)

    private val aiContributor: AiSettingsContributor?
        get() = contributors.filterIsInstance<AiSettingsContributor>().firstOrNull()

    // ─── Per-section state flows ──────────────────────────────────────────────
    // Each section feeds its own MutableStateFlow so combine doesn't block on
    // missing contributors. Each contributor's observe() is collected once.

    private val appearanceFlow = MutableStateFlow(SettingsSection.Appearance())
    private val notificationsFlow = MutableStateFlow(SettingsSection.Notifications())
    private val workScheduleFlow = MutableStateFlow(SettingsSection.WorkSchedule())
    private val greetingFlow = MutableStateFlow(SettingsSection.Greeting())
    private val aiFlow = MutableStateFlow(SettingsSection.Ai())
    private val defaultAgendaViewFlow = MutableStateFlow(SettingsSection.DefaultAgendaView())

    // ─── Ephemeral state (parallel flows — not persisted) ───────────────────

    private val aiEphemeral = MutableStateFlow(EphemeralState.Ai())
    private val agendaEphemeral = MutableStateFlow(EphemeralState.Agenda())

    // ─── Reactive state ─────────────────────────────────────────────────────

    private val _state = MutableStateFlow(SettingsUiState.Content())

    init {
        // Initialize state with all defaults immediately (before any flow emits).
        _state.value = SettingsUiState.Content(
            appearance = appearanceFlow.value,
            notifications = notificationsFlow.value,
            workSchedule = workScheduleFlow.value,
            greeting = greetingFlow.value,
            ai = aiFlow.value,
            defaultAgendaView = defaultAgendaViewFlow.value,
        )

        // Observe each contributor independently — each flow independently rebuilds
        // _state when it changes.
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

        aiEphemeral
            .onEach { aiEph ->
                _state.value = _state.value.copy(aiEphemeral = aiEph)
            }
            .launchIn(scope)

        // Bridge AI ephemeral state from the contributor's exposed StateFlows.
        aiContributor?.testResultStateFlow
            ?.onEach { testResult ->
                aiEphemeral.value = aiEphemeral.value.copy(testResult = testResult)
            }
            ?.launchIn(scope)

        aiContributor?.modelsStateFlow
            ?.onEach { models ->
                aiEphemeral.value = aiEphemeral.value.copy(models = models)
            }
            ?.launchIn(scope)

        aiContributor?.isFetchingModelsStateFlow
            ?.onEach { isFetching ->
                aiEphemeral.value = aiEphemeral.value.copy(isFetchingModels = isFetching)
            }
            ?.launchIn(scope)

        aiContributor?.fetchModelsErrorStateFlow
            ?.onEach { error ->
                aiEphemeral.value = aiEphemeral.value.copy(fetchModelsError = error)
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
            SettingsIntent.DismissError -> { /* ephemeral; cleared on next emit */ }
            SettingsIntent.OpenAttachmentsFolder -> openAttachmentsFolder()
        }
    }

    // ─── Per-section dispatchers ──────────────────────────────────────────────
    // Sync errorMessage update for snackbar feedback; persistence via contributor.

    private fun dispatchAppearance(intent: SettingsIntent.Appearance) {
        val contributor = appearanceContributor
        when (intent) {
            is SettingsIntent.Appearance.UpdateDarkTheme -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update dark theme failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching { contributor?.process(intent) }
                }
            }
            is SettingsIntent.Appearance.UpdateAccentColor -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update accent color failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching { contributor?.process(intent) }
                }
            }
            is SettingsIntent.Appearance.UpdateFontSizeScale -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update font size failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching { contributor?.process(intent) }
                }
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
                ) {
                    runCatching { contributor?.process(intent) }
                }
            }
            is SettingsIntent.Notifications.UpdateSound -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update notification sound failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching { contributor?.process(intent) }
                }
            }
            is SettingsIntent.Notifications.UpdateVibration -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update vibration failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching { contributor?.process(intent) }
                }
            }
            is SettingsIntent.Notifications.UpdateReminderDefault -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update reminder default failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching { contributor?.process(intent) }
                }
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
                ) {
                    runCatching { contributor?.process(intent) }
                }
            }
            is SettingsIntent.WorkSchedule.UpdateWorkDayEnd -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update work day end failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching { contributor?.process(intent) }
                }
            }
            is SettingsIntent.WorkSchedule.UpdateWorkLunchStart -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update lunch start failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching { contributor?.process(intent) }
                }
            }
            is SettingsIntent.WorkSchedule.UpdateWorkLunchEnd -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update lunch end failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching { contributor?.process(intent) }
                }
            }
            is SettingsIntent.WorkSchedule.UpdateWeekendSat -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update Saturday setting failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching { contributor?.process(intent) }
                }
            }
            is SettingsIntent.WorkSchedule.UpdateWeekendSun -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update Sunday setting failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching { contributor?.process(intent) }
                }
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
                ) {
                    runCatching { contributor?.process(intent) }
                }
            }
            is SettingsIntent.Greeting.UpdateAfternoonEnd -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update afternoon greeting end failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching { contributor?.process(intent) }
                }
            }
        }
    }

    private fun dispatchAi(intent: SettingsIntent.Ai) {
        scope.launch {
            aiContributor?.process(intent)
        }
    }

    private fun dispatchDefaultAgendaView(intent: SettingsIntent.DefaultAgendaView) {
        val contributor = defaultAgendaViewContributor
        when (intent) {
            is SettingsIntent.DefaultAgendaView.Update -> {
                _state.value = _state.value.copy(errorMessage = null)
                scope.fireAndForget(
                    errorLabel = "Update default agenda view failed",
                    onError = { e -> _state.value = _state.value.copy(errorMessage = e.message) },
                ) {
                    runCatching { contributor?.process(intent) }
                }
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
