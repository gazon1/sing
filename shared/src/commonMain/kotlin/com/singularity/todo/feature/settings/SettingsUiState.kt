package com.singularity.todo.feature.settings

import com.singularity.todo.core.error.AppError

/**
 * Settings screen UI state.
 * [Loading] while DataStore loads; [Content] once ready; [Error] on failure.
 */
sealed interface SettingsUiState {
    data object Loading : SettingsUiState
    data class Error(val cause: AppError) : SettingsUiState
    data class Content(
        // Appearance
        val darkTheme: Boolean = false,
        val accentColor: String = "blue",
        val fontSizeScale: Float = 1f,
        // Notifications
        val notificationsEnabled: Boolean = true,
        val notificationSound: Boolean = true,
        val notificationVibration: Boolean = true,
        val reminderDefault: ReminderOffset = ReminderOffset.AT_DUE,
        // AI Provider
        val aiApiKey: String = "",
        val aiBaseUrl: String = "https://api.openai.com/v1",
        val aiModel: String = "gpt-4o-mini",
        // Work Schedule
        val workDayStartMinutes: Int = 540,   // 09:00
        val workDayEndMinutes: Int = 1080,   // 18:00
        val workLunchStartMinutes: Int = 720, // 12:00
        val workLunchEndMinutes: Int = 780,   // 13:00
        val workWeekendSat: Boolean = false,
        val workWeekendSun: Boolean = false,
        // Greeting hours (clock hour, 0-23)
        val greetingMorningEnd: Int = 12,
        val greetingAfternoonEnd: Int = 18,
        // Account
        val userId: String = "anonymous",
    ) : SettingsUiState
}

enum class ReminderOffset(val minutes: Int, val label: String) {
    AT_DUE(0, "At due time"),
    FIFTEEN_MIN(15, "15 minutes before"),
    ONE_HOUR(60, "1 hour before"),
    ONE_DAY(1440, "1 day before"),
}

sealed interface SettingsIntent {
    // Appearance
    data class UpdateDarkTheme(val value: Boolean) : SettingsIntent
    data class UpdateAccentColor(val value: String) : SettingsIntent
    data class UpdateFontSizeScale(val value: Float) : SettingsIntent
    // Notifications
    data class UpdateNotificationsEnabled(val value: Boolean) : SettingsIntent
    data class UpdateNotificationSound(val value: Boolean) : SettingsIntent
    data class UpdateNotificationVibration(val value: Boolean) : SettingsIntent
    data class UpdateReminderDefault(val value: ReminderOffset) : SettingsIntent
    // AI Provider
    data class UpdateAiApiKey(val value: String) : SettingsIntent
    data class UpdateAiBaseUrl(val value: String) : SettingsIntent
    data class UpdateAiModel(val value: String) : SettingsIntent
    // Work Schedule
    data class UpdateWorkDayStart(val minutes: Int) : SettingsIntent
    data class UpdateWorkDayEnd(val minutes: Int) : SettingsIntent
    data class UpdateWorkLunchStart(val minutes: Int) : SettingsIntent
    data class UpdateWorkLunchEnd(val minutes: Int) : SettingsIntent
    data class UpdateWorkWeekendSat(val value: Boolean) : SettingsIntent
    data class UpdateWorkWeekendSun(val value: Boolean) : SettingsIntent
    // Greetings
    data class UpdateGreetingMorningEnd(val hour: Int) : SettingsIntent
    data class UpdateGreetingAfternoonEnd(val hour: Int) : SettingsIntent
}
