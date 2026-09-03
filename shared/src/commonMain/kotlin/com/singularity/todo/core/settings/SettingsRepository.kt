package com.singularity.todo.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.feature.settings.ReminderOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// ─── Typed preference key factory ────────────────────────────────────────────

/**
 * Creates a typed preferences key with a name and a default value.
 * Usage: `val key = keyOf("dark_theme", false)` → Preferences.Key<Boolean>
 */
inline fun <reified T> DataStore<Preferences>.keyOf(
    name: String,
    default: T
): Preferences.Key<T> = when (T::class) {
    Boolean::class -> booleanPreferencesKey(name) as Preferences.Key<T>
    Float::class -> floatPreferencesKey(name) as Preferences.Key<T>
    Int::class -> intPreferencesKey(name) as Preferences.Key<T>
    String::class -> stringPreferencesKey(name) as Preferences.Key<T>
    else -> throw IllegalArgumentException("Unsupported type: ${T::class}")
}

// ─── SettingsRepository ───────────────────────────────────────────────────────

open class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    companion object {
        // ── Appearance ────────────────────────────────────────────────────────
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        val FONT_SIZE_SCALE = floatPreferencesKey("font_size_scale")

        // ── AI ────────────────────────────────────────────────────────────────
        val AI_API_KEY = stringPreferencesKey("ai_api_key")
        val AI_PROVIDER = stringPreferencesKey("ai_provider")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val AI_SYSTEM_PROMPT = stringPreferencesKey("ai_system_prompt")

        // ── Notifications ─────────────────────────────────────────────────────
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val NOTIFICATION_SOUND = booleanPreferencesKey("notification_sound")
        val NOTIFICATION_VIBRATION = booleanPreferencesKey("notification_vibration")
        val REMINDER_DEFAULT = stringPreferencesKey("reminder_default")

        // ── Work Schedule ──────────────────────────────────────────────────────
        val WORK_DAY_START_MINUTES = intPreferencesKey("work_day_start_minutes")
        val WORK_DAY_END_MINUTES = intPreferencesKey("work_day_end_minutes")
        val WORK_LUNCH_START_MINUTES = intPreferencesKey("work_lunch_start_minutes")
        val WORK_LUNCH_END_MINUTES = intPreferencesKey("work_lunch_end_minutes")
        val WORK_WEEKEND_SAT = booleanPreferencesKey("work_weekend_sat")
        val WORK_WEEKEND_SUN = booleanPreferencesKey("work_weekend_sun")

        // ── Greetings ─────────────────────────────────────────────────────────
        val GREETING_MORNING_END = intPreferencesKey("greeting_morning_end")
        val GREETING_AFTERNOON_END = intPreferencesKey("greeting_afternoon_end")

        // ── Account ───────────────────────────────────────────────────────────
        val USER_ID = stringPreferencesKey("user_id")

        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful productivity assistant. Be concise and actionable."
    }

    // ── Appearance ─────────────────────────────────────────────────────────────

    open val darkTheme: Flow<Boolean> = dataStore.data.map { it[DARK_THEME] ?: false }
    open val accentColor: Flow<String> = dataStore.data.map { it[ACCENT_COLOR] ?: "blue" }
    open val fontSizeScale: Flow<Float> = dataStore.data.map { it[FONT_SIZE_SCALE] ?: 1f }

    // ── AI ───────────────────────────────────────────────────────────────────

    open val aiApiKey: Flow<String> = dataStore.data.map { it[AI_API_KEY] ?: "" }
    open val aiProvider: Flow<String> = dataStore.data.map { it[AI_PROVIDER] ?: "openai" }
    open val aiModel: Flow<String> = dataStore.data.map { it[AI_MODEL] ?: "gpt-4o-mini" }
    open val aiBaseUrl: Flow<String> = dataStore.data.map { it[AI_BASE_URL] ?: "https://api.openai.com/v1" }
    open val aiSystemPrompt: Flow<String> =
        dataStore.data.map { it[AI_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT }

    // ── Notifications ─────────────────────────────────────────────────────────

    open val notificationsEnabled: Flow<Boolean> =
        dataStore.data.map { it[NOTIFICATIONS_ENABLED] ?: true }
    open val notificationSound: Flow<Boolean> =
        dataStore.data.map { it[NOTIFICATION_SOUND] ?: true }
    open val notificationVibration: Flow<Boolean> =
        dataStore.data.map { it[NOTIFICATION_VIBRATION] ?: true }
    open val reminderDefault: Flow<ReminderOffset> = dataStore.data.map {
        val name = it[REMINDER_DEFAULT] ?: "AT_DUE"
        runCatching { ReminderOffset.valueOf(name) }.getOrDefault(ReminderOffset.AT_DUE)
    }

    // ── Work Schedule ──────────────────────────────────────────────────────────

    open val workDayStartMinutes: Flow<Int> =
        dataStore.data.map { it[WORK_DAY_START_MINUTES] ?: 540 }
    open val workDayEndMinutes: Flow<Int> =
        dataStore.data.map { it[WORK_DAY_END_MINUTES] ?: 1080 }
    open val workLunchStartMinutes: Flow<Int> =
        dataStore.data.map { it[WORK_LUNCH_START_MINUTES] ?: 720 }
    open val workLunchEndMinutes: Flow<Int> =
        dataStore.data.map { it[WORK_LUNCH_END_MINUTES] ?: 780 }
    open val workWeekendSat: Flow<Boolean> =
        dataStore.data.map { it[WORK_WEEKEND_SAT] ?: false }
    open val workWeekendSun: Flow<Boolean> =
        dataStore.data.map { it[WORK_WEEKEND_SUN] ?: false }

    // ── Greetings ─────────────────────────────────────────────────────────────

    open val greetingMorningEnd: Flow<Int> =
        dataStore.data.map { it[GREETING_MORNING_END] ?: 12 }
    open val greetingAfternoonEnd: Flow<Int> =
        dataStore.data.map { it[GREETING_AFTERNOON_END] ?: 18 }

    // ── Account ───────────────────────────────────────────────────────────────

    open val userId: Flow<String> = dataStore.data.map { it[USER_ID] ?: "anonymous" }

    // ── Blocking helpers (for initialization, not for UI) ──────────────────────

    fun aiApiKeyBlocking(): String? = runBlocking { dataStore.data.first()[AI_API_KEY] }
    fun aiModelBlocking(): String = runBlocking { dataStore.data.first()[AI_MODEL] } ?: "gpt-4o-mini"
    open fun userIdBlocking(): String = runBlocking { dataStore.data.first()[USER_ID] } ?: "anonymous"

    // ── Setters ───────────────────────────────────────────────────────────────

    open suspend fun setDarkTheme(value: Boolean) { dataStore.edit { it[DARK_THEME] = value } }
    open suspend fun setAccentColor(value: String) { dataStore.edit { it[ACCENT_COLOR] = value } }
    open suspend fun setFontSizeScale(value: Float) { dataStore.edit { it[FONT_SIZE_SCALE] = value } }

    open suspend fun setAiApiKey(value: String) { dataStore.edit { it[AI_API_KEY] = value } }
    open suspend fun setAiProvider(value: String) { dataStore.edit { it[AI_PROVIDER] = value } }
    open suspend fun setAiModel(value: String) { dataStore.edit { it[AI_MODEL] = value } }
    open suspend fun setAiBaseUrl(value: String) { dataStore.edit { it[AI_BASE_URL] = value } }

    open suspend fun setNotificationsEnabled(value: Boolean) {
        dataStore.edit { it[NOTIFICATIONS_ENABLED] = value }
    }
    open suspend fun setNotificationSound(value: Boolean) {
        dataStore.edit { it[NOTIFICATION_SOUND] = value }
    }
    open suspend fun setNotificationVibration(value: Boolean) {
        dataStore.edit { it[NOTIFICATION_VIBRATION] = value }
    }
    open suspend fun setReminderDefault(value: ReminderOffset) {
        dataStore.edit { it[REMINDER_DEFAULT] = value.name }
    }

    open suspend fun setWorkDayStartMinutes(value: Int) {
        dataStore.edit { it[WORK_DAY_START_MINUTES] = value }
    }
    open suspend fun setWorkDayEndMinutes(value: Int) {
        dataStore.edit { it[WORK_DAY_END_MINUTES] = value }
    }
    open suspend fun setWorkLunchStartMinutes(value: Int) {
        dataStore.edit { it[WORK_LUNCH_START_MINUTES] = value }
    }
    open suspend fun setWorkLunchEndMinutes(value: Int) {
        dataStore.edit { it[WORK_LUNCH_END_MINUTES] = value }
    }
    open suspend fun setWorkWeekendSat(value: Boolean) {
        dataStore.edit { it[WORK_WEEKEND_SAT] = value }
    }
    open suspend fun setWorkWeekendSun(value: Boolean) {
        dataStore.edit { it[WORK_WEEKEND_SUN] = value }
    }

    open suspend fun setGreetingMorningEnd(hour: Int) {
        dataStore.edit { it[GREETING_MORNING_END] = hour.coerceIn(0, 23) }
    }
    open suspend fun setGreetingAfternoonEnd(hour: Int) {
        dataStore.edit { it[GREETING_AFTERNOON_END] = hour.coerceIn(0, 23) }
    }

    open suspend fun setUserId(value: String) { dataStore.edit { it[USER_ID] = value } }
}

private fun <T> runBlocking(block: suspend () -> T): T =
    kotlinx.coroutines.runBlocking { block() }
