package com.singularity.todo.core.settings

// Provenance: ADAPTED from Orgzly (GPL-3.0) — the persisted / ephemeral settings split
//   described in the KDoc below follows that project's design.
//   Full registry: docs/legal/PROVENANCE.md

import com.singularity.todo.core.llm.AiTestResult
import com.singularity.todo.core.llm.LlmProvider
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView

/**
 * Sealed hierarchy of all settings sections.
 * Each subtype is contributed by a corresponding [SettingsContributor].
 *
 * ## Design principles
 * - Only **persisted** values belong here — no ephemeral UI state (test results,
 *   model lists, fetch errors). Those live in [EphemeralState] holders on each
 *   contributor and are combined at the ViewModel layer.
 * - Domain types (enums, value objects) are used directly rather than raw strings.
 */
sealed interface SettingsSection {

    // ── Appearance ────────────────────────────────────────────────────────────

    /** Appearance: theme mode, accent color, font scale. */
    data class Appearance(
        val themeMode: ThemeMode = ThemeMode.System,
        val accentColor: String = SettingsDefaults.Appearance.ACCENT_COLOR,
        val fontSizeScale: Float = SettingsDefaults.Appearance.FONT_SIZE_SCALE,
    ) : SettingsSection

    // ── AI Provider ───────────────────────────────────────────────────────────

    /** AI provider: backend, model, system prompt. */
    data class Ai(
        val provider: LlmProvider = LlmProvider.fromId(SettingsDefaults.Ai.PROVIDER),
        val baseUrl: String = SettingsDefaults.Ai.BASE_URL,
        val model: String = SettingsDefaults.Ai.MODEL,
        val systemPrompt: String = SettingsDefaults.Ai.SYSTEM_PROMPT,
        val testResult: AiTestResult = AiTestResult.Idle,
        val models: List<String> = emptyList(),
        val isFetchingModels: Boolean = false,
        val fetchModelsError: String? = null,
    ) : SettingsSection

    // ── Notifications ─────────────────────────────────────────────────────────

    data class Notifications(
        val enabled: Boolean = SettingsDefaults.Notifications.ENABLED,
        val sound: Boolean = SettingsDefaults.Notifications.SOUND,
        val vibration: Boolean = SettingsDefaults.Notifications.VIBRATION,
        val reminderDefault: ReminderOffset = SettingsDefaults.Notifications.REMINDER_DEFAULT,
    ) : SettingsSection

    // ── Work Schedule ─────────────────────────────────────────────────────────

    data class WorkSchedule(
        val dayStartMinutes: Int = SettingsDefaults.WorkSchedule.WORK_DAY_START_MINUTES,
        val dayEndMinutes: Int = SettingsDefaults.WorkSchedule.WORK_DAY_END_MINUTES,
        val lunchStartMinutes: Int = SettingsDefaults.WorkSchedule.WORK_LUNCH_START_MINUTES,
        val lunchEndMinutes: Int = SettingsDefaults.WorkSchedule.WORK_LUNCH_END_MINUTES,
        val weekendSat: Boolean = SettingsDefaults.WorkSchedule.WEEKEND_SAT,
        val weekendSun: Boolean = SettingsDefaults.WorkSchedule.WEEKEND_SUN,
    ) : SettingsSection

    // ── Greeting ─────────────────────────────────────────────────────────────

    data class Greeting(
        val morningEndHour: Int = SettingsDefaults.Greeting.MORNING_END_HOUR,
        val afternoonEndHour: Int = SettingsDefaults.Greeting.AFTERNOON_END_HOUR,
    ) : SettingsSection

    // ── Default Agenda View ──────────────────────────────────────────────────

    data class DefaultAgendaView(val viewId: SavedAgendaViewId? = null) : SettingsSection
}

/**
 * Ephemeral (UI-only) state that is not persisted but needs to be streamed to
 * the UI alongside the persisted [SettingsSection].
 *
 * Lives on the contributor as `StateFlow<EphemeralState.X>` and is combined
 * with persisted sections at the ViewModel layer. This mirrors Orgzly's split
 * between "default" SharedPreferences (user settings) and "state" SharedPreferences
 * (transient flags).
 */
sealed interface EphemeralState {

    data class Ai(
        val testResult: AiTestResult = AiTestResult.Idle,
        val models: List<String> = emptyList(),
        val isFetchingModels: Boolean = false,
        val fetchModelsError: String? = null,
    ) : EphemeralState

    data class Agenda(val savedViews: List<SavedAgendaView> = emptyList()) : EphemeralState

    /** Ephemeral state for the log export flow. */
    data class LogExport(
        val isExporting: Boolean = false,
        val exportedPath: String? = null,
        val errorMessage: String? = null,
    ) : EphemeralState
}

/**
 * Sealed hierarchy of all settings intents.
 * Each subtype is handled by the corresponding [SettingsContributor].
 *
 * Audited 2026-10-06 against #212, which was exactly this shape and cost a dead
 * button: `TaskDetailIntent.Domain.Start` and `TaskTimeSlotIntent.Start` were two
 * declarations of one concept, the first implemented the second's *marker*
 * interface without being one of its subtypes, and the receiving `when` matched
 * on concrete subtypes — so it matched nothing and fell into a silent `else`.
 *
 * This hierarchy is not that shape, and the difference is structural rather than
 * incidental: **every concept is declared once**. `Appearance`, `Ai`,
 * `Notifications`, `WorkSchedule`, `Greeting` and `DefaultAgendaView` each own
 * their intents outright and share only the `SettingsIntent` root, which is
 * dispatched at the top level rather than forwarded across. A second declaration
 * of an existing concept is the thing that has to be looked for when a button
 * goes dead; there is none here.
 *
 * The residual risk is one step removed from that: `SettingsViewModel.dispatch`
 * hands the intent to a contributor through an unchecked cast, and its safety
 * rests on `onIntent`'s `when` pairing each contributor with its own section. That
 * is a convention, not a check — the same shape of claim that #212's comment made,
 * and the thing to verify first if a settings control is ever found inert.
 */
sealed interface SettingsIntent : com.singularity.todo.core.ui.MviIntent {

    // ── Appearance ────────────────────────────────────────────────────────────

    sealed interface Appearance : SettingsIntent {
        data class UpdateThemeMode(val value: ThemeMode) : Appearance
        data class UpdateAccentColor(val value: String) : Appearance
        data class UpdateFontSizeScale(val value: Float) : Appearance
    }

    // ── AI Provider ───────────────────────────────────────────────────────────

    sealed interface Ai : SettingsIntent {
        data class UpdateProvider(val value: LlmProvider) : Ai
        data class UpdateBaseUrl(val value: String) : Ai
        data class UpdateModel(val value: String) : Ai
        data class UpdateSystemPrompt(val value: String) : Ai

        /** API key — written directly to SecureStorage, never debounced through apply(). */
        data class UpdateApiKey(val value: String) : Ai
        data object TestConnection : Ai
        data object FetchModels : Ai
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    sealed interface Notifications : SettingsIntent {
        data class UpdateEnabled(val value: Boolean) : Notifications
        data class UpdateSound(val value: Boolean) : Notifications
        data class UpdateVibration(val value: Boolean) : Notifications
        data class UpdateReminderDefault(val value: ReminderOffset) : Notifications
    }

    // ── Work Schedule ─────────────────────────────────────────────────────────

    sealed interface WorkSchedule : SettingsIntent {
        data class UpdateWorkDayStart(val minutes: Int) : WorkSchedule
        data class UpdateWorkDayEnd(val minutes: Int) : WorkSchedule
        data class UpdateWorkLunchStart(val minutes: Int) : WorkSchedule
        data class UpdateWorkLunchEnd(val minutes: Int) : WorkSchedule
        data class UpdateWeekendSat(val value: Boolean) : WorkSchedule
        data class UpdateWeekendSun(val value: Boolean) : WorkSchedule
    }

    // ── Greeting ─────────────────────────────────────────────────────────────

    sealed interface Greeting : SettingsIntent {
        data class UpdateMorningEnd(val hour: Int) : Greeting
        data class UpdateAfternoonEnd(val hour: Int) : Greeting
    }

    // ── Default Agenda View ──────────────────────────────────────────────────

    sealed interface DefaultAgendaView : SettingsIntent {
        data class Update(val viewId: SavedAgendaViewId?) : DefaultAgendaView
    }

    /** Clears any errorMessage in the UI state. */
    data object DismissError : SettingsIntent

    /** Opens the file manager at the attachments folder. */
    data object OpenAttachmentsFolder : SettingsIntent

    /** Triggers log bundle export and share. */
    data object ExportLogs : SettingsIntent
}
