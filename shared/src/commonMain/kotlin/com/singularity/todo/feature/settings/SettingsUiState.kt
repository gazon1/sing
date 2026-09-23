package com.singularity.todo.feature.settings

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.llm.AiTestResult
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.core.settings.EphemeralState
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.core.ui.theme.SingularityAccents
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaView

/**
 * Re-exports [SettingsIntent] from [core.settings] so that existing importers
 * (sub-screens, ViewModels) don't need to change their import paths.
 */
typealias SettingsIntent = SettingsIntent

/**
 * Settings screen UI state — thin sealed interface.
 * [Loading] while DataStore loads; [Content] once ready; [Error] on failure.
 *
 * ## Architecture note
 * [Content] combines typed contributor sections ([appearance], [ai], etc.) with
 * flat legacy fields for sub-screens that are not yet migrated (see Phase 6).
 * Ephemeral state ([aiEphemeral], [agendaEphemeral]) is kept separate from
 * persisted [SettingsSection] data.
 *
 * Sub-screens will migrate from flat fields to typed section fields in Phase 6.
 */
sealed interface SettingsUiState {
    data object Loading : SettingsUiState
    data class Error(val cause: Throwable) : SettingsUiState
    data class Content(
        // ── Typed contributor sections ─────────────────────────────────────────
        val appearance: SettingsSection.Appearance = SettingsSection.Appearance(),
        val notifications: SettingsSection.Notifications = SettingsSection.Notifications(),
        val workSchedule: SettingsSection.WorkSchedule = SettingsSection.WorkSchedule(),
        val greeting: SettingsSection.Greeting = SettingsSection.Greeting(),
        val ai: SettingsSection.Ai = SettingsSection.Ai(),
        val defaultAgendaView: SettingsSection.DefaultAgendaView = SettingsSection.DefaultAgendaView(),

        // ── Ephemeral state (not persisted) ────────────────────────────────────
        /** AI test result, fetched model list, fetch errors — UI-only state. */
        val aiEphemeral: EphemeralState.Ai = EphemeralState.Ai(),
        /** Saved agenda views list — refreshed from DB on each observation. */
        val agendaEphemeral: EphemeralState.Agenda = EphemeralState.Agenda(),

        // ── Legacy flat fields (backward compatibility — migrated in Phase 6) ────
        // Appearance (mirrors appearance.*)
        val darkTheme: Boolean = false,
        val accentColor: String = "blue",
        val fontSizeScale: Float = 1f,
        // AI Provider (mirrors ai.*)
        val aiProvider: String = "openai",
        val aiBaseUrl: String = "https://api.openai.com/v1",
        val aiModel: String = "gpt-4o-mini",
        val aiSystemPrompt: String = "You are a helpful productivity assistant. Be concise and actionable.",
        val aiTestResult: AiTestResult = AiTestResult.Idle,
        val aiModels: List<String> = emptyList(),
        val isFetchingAiModels: Boolean = false,
        val fetchAiModelsError: String? = null,
        // Notifications
        val notificationsEnabled: Boolean = true,
        val notificationSound: Boolean = true,
        val notificationVibration: Boolean = true,
        val reminderDefault: ReminderOffset = ReminderOffset.AT_DUE,
        // Work Schedule
        val workDayStartMinutes: Int = 540,
        val workDayEndMinutes: Int = 1080,
        val workLunchStartMinutes: Int = 720,
        val workLunchEndMinutes: Int = 780,
        val workWeekendSat: Boolean = false,
        val workWeekendSun: Boolean = false,
        // Greeting
        val greetingMorningEnd: Int = 12,
        val greetingAfternoonEnd: Int = 18,
        // Account
        val userId: String = UserId.anonymous.value,
        // Agenda
        val defaultSavedAgendaViewId: SavedAgendaViewId? = null,
        val savedAgendaViews: List<SavedAgendaView> = emptyList(),
        // Error state
        val errorMessage: String? = null,
    ) : SettingsUiState
}
