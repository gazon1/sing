package com.singularity.todo.core.settings

import kotlinx.serialization.Serializable

/**
 * JSON-serializable snapshot of all user settings (no ephemeral state).
 *
 * Produced by [SettingsExporter] and consumed by [SettingsImporter].
 * Schema version is incremented when the shape of any section changes.
 *
 * Design:
 * - All fields are primitive or enum (no domain value objects) so the snapshot
 *   is self-contained and survives across app versions.
 * - AI ephemeral fields (testResult, models, fetch error) are NOT included —
 *   they are runtime-only and restored by re-running TestConnection.
 * - Missing optional fields in older snapshots use [SettingsDefaults].
 */
@Serializable
data class SettingsSnapshot(
    val schemaVersion: Int = CURRENT_VERSION,
    val appearance: AppearanceSnapshot,
    val ai: AiSnapshot,
    val notifications: NotificationsSnapshot,
    val workSchedule: WorkScheduleSnapshot,
    val greeting: GreetingSnapshot,
    val defaultAgendaView: DefaultAgendaViewSnapshot,
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}

@Serializable
data class AppearanceSnapshot(
    /**
     * Theme mode as a storage string: "system", "light", "dark".
     * When empty and [darkThemeForMigration] is present, the v1 migration applies.
     * Sentinel `__UNSET__` means "v2 format with System default — no migration needed".
     */
    val themeMode: String = "__UNSET__",
    val accentColor: String = "blue",
    val fontSizeScale: Float = 1f,
    /**
     * Retained for v1 → v2 JSON migration. When [themeMode] is `__UNSET__`
     * (v2 format) this field is absent (defaults to false). When v1 JSON is
     * deserialized, this carries the old boolean value and triggers migration.
     */
    val darkThemeForMigration: Boolean = false,
)

@Serializable
data class AiSnapshot(
    val providerId: String = "openai",
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val systemPrompt: String = "You are a helpful productivity assistant. Be concise and actionable.",
)

@Serializable
data class NotificationsSnapshot(
    val enabled: Boolean = true,
    val sound: Boolean = true,
    val vibration: Boolean = true,
    val reminderDefaultMinutes: Int = 0, // ReminderOffset minutes, not the enum
)

@Serializable
data class WorkScheduleSnapshot(
    val dayStartMinutes: Int = 540,
    val dayEndMinutes: Int = 1080,
    val lunchStartMinutes: Int = 720,
    val lunchEndMinutes: Int = 780,
    val weekendSat: Boolean = false,
    val weekendSun: Boolean = false,
)

@Serializable
data class GreetingSnapshot(val morningEndHour: Int = 12, val afternoonEndHour: Int = 18)

@Serializable
data class DefaultAgendaViewSnapshot(
    val viewId: String? = null, // SavedAgendaViewId.raw, null means "none"
)
