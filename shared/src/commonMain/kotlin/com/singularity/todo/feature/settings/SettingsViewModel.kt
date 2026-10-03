package com.singularity.todo.feature.settings

import com.singularity.todo.core.appearance.AppearanceContributor
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.files.FileRevealer
import com.singularity.todo.core.files.FileSharePort
import com.singularity.todo.core.log.LogBundleExporter
import com.singularity.todo.core.notifications.NotificationsContributor
import com.singularity.todo.core.schedule.GreetingContributor
import com.singularity.todo.core.schedule.WorkScheduleContributor
import com.singularity.todo.core.settings.EphemeralState
import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.core.ui.MviViewModel
import com.singularity.todo.feature.agenda.DefaultAgendaViewContributor
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.ai.AiContributor
import kotlinx.coroutines.flow.MutableStateFlow
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
 * - Each contributor's [observe][SettingsContributor.observe] feeds a dedicated
 *   [MutableStateFlow] via [bind]; any change rebuilds the merged [Content] state.
 * - [onIntent] routes to the section's contributor through the single [dispatch]
 *   helper, which routes failures into [SettingsUiState.Content.errorMessage].
 */
class SettingsViewModel(
    private val appearanceContributor: AppearanceContributor?,
    private val notificationsContributor: NotificationsContributor?,
    private val workScheduleContributor: WorkScheduleContributor?,
    private val greetingContributor: GreetingContributor?,
    private val aiContributor: AiContributor?,
    private val defaultAgendaViewContributor: DefaultAgendaViewContributor?,
    private val savedAgendaViewsRepo: SavedAgendaViewsRepository,
    private val fileRevealer: FileRevealer,
    private val logBundleExporter: LogBundleExporter,
    private val fileSharePort: FileSharePort,
    scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<SettingsUiState.Content, SettingsIntent, Nothing>(
        initialState = SettingsUiState.Content(),
        scope = scope,
    ) {

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
    private val logExportEphemeral = MutableStateFlow(EphemeralState.LogExport())

    // ─── UI state ────────────────────────────────────────────────────────────
    // Owned by [MviViewModel]; the per-section flows above are the inputs that
    // [rebuildState] folds into [currentState].

    init {
        // Seed state with all defaults immediately (before any flow emits).
        setState(
            SettingsUiState.Content(
                appearance = appearanceFlow.value,
                notifications = notificationsFlow.value,
                workSchedule = workScheduleFlow.value,
                greeting = greetingFlow.value,
                ai = aiFlow.value,
                defaultAgendaView = defaultAgendaViewFlow.value,
            ),
        )

        bind(appearanceContributor, appearanceFlow)
        bind(notificationsContributor, notificationsFlow)
        bind(workScheduleContributor, workScheduleFlow)
        bind(greetingContributor, greetingFlow)
        bind(aiContributor, aiFlow)
        bind(defaultAgendaViewContributor, defaultAgendaViewFlow)

        savedAgendaViewsRepo.observeAll()
            .onEach { views ->
                agendaEphemeral.value = agendaEphemeral.value.copy(savedViews = views)
                rebuildState()
            }
            .launchIn(vmScope)

        // Bridge AI ephemeral state from the single combined flow.
        aiContributor?.ephemeralStateFlow
            ?.onEach { aiEph ->
                aiEphemeral.value = aiEph
                updateState { it.copy(aiEphemeral = aiEph) }
            }
            ?.launchIn(vmScope)
    }

    /** Collects [contributor]'s section flow into [slot]; every change rebuilds the merged state. */
    private fun <S : SettingsSection> bind(contributor: SettingsContributor<S, *>?, slot: MutableStateFlow<S>) {
        contributor?.observe()
            ?.onEach { section ->
                slot.value = section
                rebuildState()
            }
            ?.launchIn(vmScope)
    }

    /** Rebuilds the full Content from current per-section flows. */
    private fun rebuildState() {
        updateState {
            it.copy(
                appearance = appearanceFlow.value,
                notifications = notificationsFlow.value,
                workSchedule = workScheduleFlow.value,
                greeting = greetingFlow.value,
                ai = aiFlow.value,
                defaultAgendaView = defaultAgendaViewFlow.value,
                logExportEphemeral = logExportEphemeral.value,
            )
        }
    }

    // ─── Intent ───────────────────────────────────────────────────────────────

    override fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.Appearance -> dispatch(appearanceContributor, "Update appearance failed", intent)

            is SettingsIntent.Notifications -> dispatch(notificationsContributor, "Update notifications failed", intent)

            is SettingsIntent.WorkSchedule -> dispatch(workScheduleContributor, "Update work schedule failed", intent)

            is SettingsIntent.Greeting -> dispatch(greetingContributor, "Update greeting failed", intent)

            is SettingsIntent.Ai -> dispatch(aiContributor, "AI ${intent::class.simpleName} failed", intent)

            is SettingsIntent.DefaultAgendaView -> dispatch(
                defaultAgendaViewContributor,
                "Update default agenda view failed",
                intent,
            )

            SettingsIntent.DismissError -> { /* ephemeral; cleared on next emit */ }

            SettingsIntent.OpenAttachmentsFolder -> openAttachmentsFolder()

            SettingsIntent.ExportLogs -> exportLogs()
        }
    }

    /**
     * Routes [intent] to [contributor], routing failures into the state's error field.
     *
     * The cast is safe: [onIntent]'s `when` guarantees the contributor and
     * intent belong to the same section (e.g. `NotificationsContributor` only
     * receives `SettingsIntent.Notifications`).
     */
    private fun dispatch(contributor: SettingsContributor<*, *>?, errorLabel: String, intent: SettingsIntent) {
        updateState { it.copy(errorMessage = null) }
        catchTo(errorLabel, { msg -> updateState { it.copy(errorMessage = msg) } }) {
            runCatching {
                @Suppress("UNCHECKED_CAST")
                (contributor as SettingsContributor<SettingsSection, SettingsIntent>).process(intent)
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun openAttachmentsFolder() {
        vmScope.launch {
            fileRevealer.revealAttachmentsFolder(fileRevealer.attachmentsBasePath())
        }
    }

    private fun exportLogs() {
        vmScope.launch {
            logExportEphemeral.value = EphemeralState.LogExport(isExporting = true)
            rebuildState()

            val result = logBundleExporter.export()

            val newState = result.fold(
                onSuccess = { path ->
                    EphemeralState.LogExport(isExporting = false, exportedPath = path)
                },
                onFailure = { error ->
                    EphemeralState.LogExport(
                        isExporting = false,
                        errorMessage = error.message ?: "Export failed",
                    )
                },
            )
            logExportEphemeral.value = newState
            rebuildState()

            // Share the archive if export succeeded
            result.getOrNull()?.let { path ->
                fileSharePort.shareFile(path, "application/zip")
            }
        }
    }

    /**
     * Updates the AI API key via the contributor (writes to SecureStorage).
     */
    fun updateAiApiKey(value: String) {
        vmScope.launch {
            aiContributor?.updateApiKey(value)
        }
    }
}
