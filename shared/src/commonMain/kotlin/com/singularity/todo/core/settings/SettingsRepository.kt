package com.singularity.todo.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.llm.SettingsReader
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ─── Typed preference key factory ────────────────────────────────────────────

/**
 * Creates a typed preferences key with a name and a default value.
 * Usage: `val key = keyOf("dark_theme", false)` → Preferences.Key<Boolean>
 */
@Suppress("UNCHECKED_CAST") // Safe: type is verified at runtime via `when (T::class)`
inline fun <reified T> DataStore<Preferences>.keyOf(
    name: String,
    default: T,
): Preferences.Key<T> = when (T::class) {
    Boolean::class -> booleanPreferencesKey(name) as Preferences.Key<T>
    Float::class -> floatPreferencesKey(name) as Preferences.Key<T>
    Int::class -> intPreferencesKey(name) as Preferences.Key<T>
    String::class -> stringPreferencesKey(name) as Preferences.Key<T>
    else -> throw IllegalArgumentException("Unsupported type: ${T::class}")
}

// ─── SettingsRepository ───────────────────────────────────────────────────────

/**
 * Contract for user settings.
 */
interface SettingsRepository : SettingsReader {

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful productivity assistant. Be concise and actionable."
        const val DEFAULT_AI_MODEL = "gpt-4o-mini"
    }

    // ── Appearance ─────────────────────────────────────────────────────────────

    val darkTheme: Flow<Boolean>
    val accentColor: Flow<String>
    val fontSizeScale: Flow<Float>

    // ── AI ───────────────────────────────────────────────────────────────────
    // The API key is intentionally NOT here — it lives in [SecureStoragePort]
    // (hardware-backed keychain on Android, libsecret on Linux). Only
    // non-secret AI settings live in this repository.

    // aiProvider, aiModel, aiBaseUrl inherited from SettingsReader
    val aiSystemPrompt: Flow<String>

    // ── Notifications ─────────────────────────────────────────────────────────

    val notificationsEnabled: Flow<Boolean>
    val notificationSound: Flow<Boolean>
    val notificationVibration: Flow<Boolean>
    val reminderDefault: Flow<ReminderOffset>

    // ── Work Schedule ──────────────────────────────────────────────────────────

    val workDayStartMinutes: Flow<Int>
    val workDayEndMinutes: Flow<Int>
    val workLunchStartMinutes: Flow<Int>
    val workLunchEndMinutes: Flow<Int>
    val workWeekendSat: Flow<Boolean>
    val workWeekendSun: Flow<Boolean>

    // ── Greetings ─────────────────────────────────────────────────────────────

    val greetingMorningEnd: Flow<Int>
    val greetingAfternoonEnd: Flow<Int>

    // ── Account ───────────────────────────────────────────────────────────────

    val userId: Flow<String>

    // ── Agenda ────────────────────────────────────────────────────────────────

    val defaultSavedAgendaViewId: Flow<SavedAgendaViewId?>

    // ── Setters ───────────────────────────────────────────────────────────────

    suspend fun setDarkTheme(value: Boolean)
    suspend fun setAccentColor(value: String)
    suspend fun setFontSizeScale(value: Float)

    suspend fun setAiProvider(value: String)
    suspend fun setAiModel(value: String)
    suspend fun setAiBaseUrl(value: String)
    suspend fun setAiSystemPrompt(value: String)

    suspend fun setNotificationsEnabled(value: Boolean)
    suspend fun setNotificationSound(value: Boolean)
    suspend fun setNotificationVibration(value: Boolean)
    suspend fun setReminderDefault(value: ReminderOffset)

    suspend fun setWorkDayStartMinutes(value: Int)
    suspend fun setWorkDayEndMinutes(value: Int)
    suspend fun setWorkLunchStartMinutes(value: Int)
    suspend fun setWorkLunchEndMinutes(value: Int)
    suspend fun setWorkWeekendSat(value: Boolean)
    suspend fun setWorkWeekendSun(value: Boolean)

    suspend fun setGreetingMorningEnd(hour: Int)
    suspend fun setGreetingAfternoonEnd(hour: Int)

    suspend fun setUserId(value: String)

    suspend fun setDefaultSavedAgendaViewId(id: SavedAgendaViewId?)
}

/**
 * Production [SettingsRepository] backed by DataStore.
 */
class DataStoreSettingsRepository(private val dataStore: DataStore<Preferences>) : SettingsRepository {

    companion object {
        // ── Appearance ────────────────────────────────────────────────────────────
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        val FONT_SIZE_SCALE = floatPreferencesKey("font_size_scale")

        // ── AI ────────────────────────────────────────────────────────────────
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

        // ── Agenda ────────────────────────────────────────────────────────────
        val DEFAULT_SAVED_AGENDA_VIEW_ID = stringPreferencesKey("default_saved_agenda_view_id")
    }

    // ── Appearance ─────────────────────────────────────────────────────────────

    override val darkTheme: Flow<Boolean> = dataStore.data.map { it[DARK_THEME] ?: false }
    override val accentColor: Flow<String> = dataStore.data.map { it[ACCENT_COLOR] ?: "blue" }
    override val fontSizeScale: Flow<Float> = dataStore.data.map { it[FONT_SIZE_SCALE] ?: 1f }

    // ── AI ───────────────────────────────────────────────────────────────────

    override val aiProvider: Flow<String> = dataStore.data.map { it[AI_PROVIDER] ?: "openai" }
    override val aiModel: Flow<String> = dataStore.data.map { it[AI_MODEL] ?: SettingsRepository.DEFAULT_AI_MODEL }
    override val aiBaseUrl: Flow<String> = dataStore.data.map { it[AI_BASE_URL] ?: "https://api.openai.com/v1" }
    override val aiSystemPrompt: Flow<String> =
        dataStore.data.map { it[AI_SYSTEM_PROMPT] ?: SettingsRepository.DEFAULT_SYSTEM_PROMPT }

    // ── Notifications ─────────────────────────────────────────────────────────

    override val notificationsEnabled: Flow<Boolean> =
        dataStore.data.map { it[NOTIFICATIONS_ENABLED] ?: true }
    override val notificationSound: Flow<Boolean> =
        dataStore.data.map { it[NOTIFICATION_SOUND] ?: true }
    override val notificationVibration: Flow<Boolean> =
        dataStore.data.map { it[NOTIFICATION_VIBRATION] ?: true }
    override val reminderDefault: Flow<ReminderOffset> = dataStore.data.map {
        val name = it[REMINDER_DEFAULT] ?: "AT_DUE"
        runCatching { ReminderOffset.valueOf(name) }.getOrDefault(ReminderOffset.AT_DUE)
    }

    // ── Work Schedule ──────────────────────────────────────────────────────────

    override val workDayStartMinutes: Flow<Int> =
        dataStore.data.map { it[WORK_DAY_START_MINUTES] ?: 540 }
    override val workDayEndMinutes: Flow<Int> =
        dataStore.data.map { it[WORK_DAY_END_MINUTES] ?: 1080 }
    override val workLunchStartMinutes: Flow<Int> =
        dataStore.data.map { it[WORK_LUNCH_START_MINUTES] ?: 720 }
    override val workLunchEndMinutes: Flow<Int> =
        dataStore.data.map { it[WORK_LUNCH_END_MINUTES] ?: 780 }
    override val workWeekendSat: Flow<Boolean> =
        dataStore.data.map { it[WORK_WEEKEND_SAT] ?: false }
    override val workWeekendSun: Flow<Boolean> =
        dataStore.data.map { it[WORK_WEEKEND_SUN] ?: false }

    // ── Greetings ─────────────────────────────────────────────────────────────

    override val greetingMorningEnd: Flow<Int> =
        dataStore.data.map { it[GREETING_MORNING_END] ?: 12 }
    override val greetingAfternoonEnd: Flow<Int> =
        dataStore.data.map { it[GREETING_AFTERNOON_END] ?: 18 }

    // ── Account ───────────────────────────────────────────────────────────────

    override val userId: Flow<String> = dataStore.data.map { it[USER_ID] ?: UserId.anonymous.value }

    // ── Agenda ────────────────────────────────────────────────────────────────

    override val defaultSavedAgendaViewId: Flow<SavedAgendaViewId?> = dataStore.data.map {
        it[DEFAULT_SAVED_AGENDA_VIEW_ID]?.let { raw -> runCatching { SavedAgendaViewId.fromString(raw) }.getOrNull() }
    }

    // ── Setters ───────────────────────────────────────────────────────────────

    override suspend fun setDarkTheme(value: Boolean) {
        dataStore.edit { it[DARK_THEME] = value }
    }
    override suspend fun setAccentColor(value: String) {
        dataStore.edit { it[ACCENT_COLOR] = value }
    }
    override suspend fun setFontSizeScale(value: Float) {
        dataStore.edit { it[FONT_SIZE_SCALE] = value }
    }

    override suspend fun setAiProvider(value: String) {
        dataStore.edit { it[AI_PROVIDER] = value }
    }
    override suspend fun setAiModel(value: String) {
        dataStore.edit { it[AI_MODEL] = value }
    }
    override suspend fun setAiBaseUrl(value: String) {
        dataStore.edit { it[AI_BASE_URL] = value }
    }
    override suspend fun setAiSystemPrompt(value: String) {
        dataStore.edit { it[AI_SYSTEM_PROMPT] = value }
    }

    override suspend fun setNotificationsEnabled(value: Boolean) {
        dataStore.edit { it[NOTIFICATIONS_ENABLED] = value }
    }
    override suspend fun setNotificationSound(value: Boolean) {
        dataStore.edit { it[NOTIFICATION_SOUND] = value }
    }
    override suspend fun setNotificationVibration(value: Boolean) {
        dataStore.edit { it[NOTIFICATION_VIBRATION] = value }
    }
    override suspend fun setReminderDefault(value: ReminderOffset) {
        dataStore.edit { it[REMINDER_DEFAULT] = value.name }
    }

    override suspend fun setWorkDayStartMinutes(value: Int) {
        dataStore.edit { it[WORK_DAY_START_MINUTES] = value }
    }
    override suspend fun setWorkDayEndMinutes(value: Int) {
        dataStore.edit { it[WORK_DAY_END_MINUTES] = value }
    }
    override suspend fun setWorkLunchStartMinutes(value: Int) {
        dataStore.edit { it[WORK_LUNCH_START_MINUTES] = value }
    }
    override suspend fun setWorkLunchEndMinutes(value: Int) {
        dataStore.edit { it[WORK_LUNCH_END_MINUTES] = value }
    }
    override suspend fun setWorkWeekendSat(value: Boolean) {
        dataStore.edit { it[WORK_WEEKEND_SAT] = value }
    }
    override suspend fun setWorkWeekendSun(value: Boolean) {
        dataStore.edit { it[WORK_WEEKEND_SUN] = value }
    }

    override suspend fun setGreetingMorningEnd(hour: Int) {
        dataStore.edit { it[GREETING_MORNING_END] = hour.coerceIn(0, 23) }
    }
    override suspend fun setGreetingAfternoonEnd(hour: Int) {
        dataStore.edit { it[GREETING_AFTERNOON_END] = hour.coerceIn(0, 23) }
    }

    override suspend fun setUserId(value: String) {
        dataStore.edit { it[USER_ID] = value }
    }

    override suspend fun setDefaultSavedAgendaViewId(id: SavedAgendaViewId?) {
        dataStore.edit {
            if (id == null) {
                it.remove(DEFAULT_SAVED_AGENDA_VIEW_ID)
            } else {
                it[DEFAULT_SAVED_AGENDA_VIEW_ID] = id.raw
            }
        }
    }
}
