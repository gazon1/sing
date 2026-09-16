package com.singularity.todo.core.settings

import com.singularity.todo.core.llm.AiTestResult
import com.singularity.todo.core.llm.LlmProvider
import com.singularity.todo.core.reminders.ReminderOffset

/**
 * Sealed hierarchy of all settings sections.
 * Each subtype is contributed by a corresponding [SettingsContributor].
 */
sealed interface SettingsSection {
    /** Appearance: dark theme, accent color, font scale. */
    data class Appearance(
        val darkTheme: Boolean = false,
        val accentColor: String = "blue",
        val fontSizeScale: Float = 1f,
    ) : SettingsSection

    /** AI provider: backend, model, system prompt, plus UI-only test/fetch state. */
    data class Ai(
        val provider: LlmProvider = LlmProvider.OPENAI,
        val baseUrl: String = "https://api.openai.com/v1",
        val model: String = "gpt-4o-mini",
        val systemPrompt: String = "You are a helpful productivity assistant. Be concise and actionable.",
        val testResult: AiTestResult = AiTestResult.Idle,
        val models: List<String> = emptyList(),
        val isFetchingModels: Boolean = false,
        val fetchModelsError: String? = null,
    ) : SettingsSection
}

/**
 * Sealed hierarchy of all settings intents.
 * Each subtype is handled by the corresponding [SettingsContributor].
 */
sealed interface SettingsIntent {
    sealed interface Appearance : SettingsIntent {
        data class UpdateDarkTheme(val value: Boolean) : Appearance
        data class UpdateAccentColor(val value: String) : Appearance
        data class UpdateFontSizeScale(val value: Float) : Appearance
    }

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

    // Dead-weight intents — notifications, work schedule, greeting.
    // These will be migrated to their own contributors in a future iteration.
    // For now, SettingsViewModel routes them directly to SettingsRepository.
    sealed interface Notifications : SettingsIntent {
        data class UpdateEnabled(val value: Boolean) : Notifications
        data class UpdateSound(val value: Boolean) : Notifications
        data class UpdateVibration(val value: Boolean) : Notifications
        data class UpdateReminderDefault(val value: ReminderOffset) : Notifications
    }

    sealed interface WorkSchedule : SettingsIntent {
        data class UpdateWorkDayStart(val minutes: Int) : WorkSchedule
        data class UpdateWorkDayEnd(val minutes: Int) : WorkSchedule
        data class UpdateWorkLunchStart(val minutes: Int) : WorkSchedule
        data class UpdateWorkLunchEnd(val minutes: Int) : WorkSchedule
        data class UpdateWeekendSat(val value: Boolean) : WorkSchedule
        data class UpdateWeekendSun(val value: Boolean) : WorkSchedule
    }

    sealed interface Greeting : SettingsIntent {
        data class UpdateMorningEnd(val hour: Int) : Greeting
        data class UpdateAfternoonEnd(val hour: Int) : Greeting
    }
}
