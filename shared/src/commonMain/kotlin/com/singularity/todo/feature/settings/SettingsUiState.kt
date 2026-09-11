package com.singularity.todo.feature.settings

import com.singularity.todo.core.llm.AiTestResult
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection

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
 * [Content] combines:
 * - Typed contributor sections ([appearance], [ai]) — the new contributor pattern
 * - Flat fields for legacy sub-screens that still read directly from [Content]
 *   (notifications, work schedule, greeting, account) — these will be migrated to
 *   their own contributors in a future iteration.
 *
 * Sub-screens (Interface, AI Provider, Notifications, etc.) are gradually being
 * converted to use typed sections from `core.settings` directly instead of the
 * flat legacy fields.
 */
sealed interface SettingsUiState {
    data object Loading : SettingsUiState
    data class Error(val cause: Throwable) : SettingsUiState
    data class Content(
        // ── Typed contributor sections (new pattern) ────────────────────────────
        val appearance: SettingsSection.Appearance = SettingsSection.Appearance(),
        val ai: SettingsSection.Ai = SettingsSection.Ai(),

        // ── Legacy flat fields (backward compatibility with existing sub-screens) ──
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
        val userId: String = "anonymous",
    ) : SettingsUiState
}
