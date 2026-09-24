package com.singularity.todo.core.settings

import com.singularity.todo.core.reminders.ReminderOffset

/**
 * Single source of truth for all settings default values.
 *
 * Replaces 4-way duplicated defaults previously scattered across:
 * - [DataStoreSettingsRepository] Flow `?: default` fallbacks
 * - [SettingsSection] data-class default parameters
 * - [SettingsUiState.Content] property defaults
 * - [test/fakes/FakeRepositories] MutableStateFlow initial values
 *
 * Each nested object corresponds to one [SettingsSection] subtype.
 * Enum defaults are stored as their raw values (string/int) because DataStore
 * serialises primitives — conversion to/from domain enums happens at the
 * repository boundary.
 */
object SettingsDefaults {

    val SCHEMA_VERSION: Int = 1

    // ── Appearance ────────────────────────────────────────────────────────

    object Appearance {
        const val DARK_THEME: Boolean = false

        // Not const: depends on enum entry property access at runtime.
        val ACCENT_COLOR: String = "blue"
        const val FONT_SIZE_SCALE: Float = 1f
    }

    // ── AI ────────────────────────────────────────────────────────────────

    object Ai {
        // Not const: depends on enum entry property access at runtime.
        val PROVIDER: String = "openai"
        const val MODEL: String = "gpt-4o-mini"
        const val BASE_URL: String = "https://api.openai.com/v1"
        const val SYSTEM_PROMPT: String =
            "You are a helpful productivity assistant. Be concise and actionable."
    }

    // ── Notifications ─────────────────────────────────────────────────────

    object Notifications {
        const val ENABLED: Boolean = true
        const val SOUND: Boolean = true
        const val VIBRATION: Boolean = true
        val REMINDER_DEFAULT: ReminderOffset = ReminderOffset.AT_DUE
    }

    // ── Work Schedule ─────────────────────────────────────────────────────

    object WorkSchedule {
        const val WORK_DAY_START_MINUTES: Int = 540 // 09:00
        const val WORK_DAY_END_MINUTES: Int = 1080 // 18:00
        const val WORK_LUNCH_START_MINUTES: Int = 720 // 12:00
        const val WORK_LUNCH_END_MINUTES: Int = 780 // 13:00
        const val WEEKEND_SAT: Boolean = false
        const val WEEKEND_SUN: Boolean = false
    }

    // ── Greeting ─────────────────────────────────────────────────────────

    object Greeting {
        const val MORNING_END_HOUR: Int = 12
        const val AFTERNOON_END_HOUR: Int = 18
    }

    // ── Account ───────────────────────────────────────────────────────────

    // Not const: depends on UserId.anonymous.value (runtime property access).
    val USER_ID: String = "anonymous"
}
