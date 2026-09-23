package com.singularity.todo.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.llm.SettingsReader
import com.singularity.todo.core.notifications.DataStoreNotificationsSettingsRepository
import com.singularity.todo.core.notifications.NotificationsSettingsRepository
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.core.schedule.DataStoreGreetingSettingsRepository
import com.singularity.todo.core.schedule.DataStoreWorkScheduleSettingsRepository
import com.singularity.todo.core.schedule.GreetingSettingsRepository
import com.singularity.todo.core.schedule.WorkScheduleSettingsRepository
import com.singularity.todo.feature.agenda.DataStoreDefaultAgendaViewSettingsRepository
import com.singularity.todo.feature.agenda.DefaultAgendaViewSettingsRepository
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

// ─── Namespace ────────────────────────────────────────────────────────────────

/**
 * Logical namespace prefix for each settings section.
 * Keys in DataStore are prefixed so a section can be cleared/exported as a unit.
 */
object SettingsNamespace {
    const val APPEARANCE = "appearance"
    const val AI = "ai"
    const val NOTIFICATIONS = "notifications"
    const val WORK_SCHEDULE = "schedule"
    const val GREETING = "greeting"
    const val ACCOUNT = "account"
    const val AGENDA = "agenda"

    fun key(ns: String, name: String): String = "$ns.$name"
}

// ─── SettingsRepository ───────────────────────────────────────────────────────

/**
 * Contract for user settings.
 *
 * Per-section repositories delegate to the same [DataStore] instance but own
 * their own namespace prefix. The flat getters below are preserved for
 * backward compatibility during migration; new code should use the
 * per-section repositories.
 */
interface SettingsRepository : SettingsReader {

    companion object {
        // Re-export AI defaults for callers that need the raw string constants.
        const val DEFAULT_SYSTEM_PROMPT = SettingsDefaults.Ai.SYSTEM_PROMPT
        const val DEFAULT_AI_MODEL = SettingsDefaults.Ai.MODEL
    }

    // ── Per-section repositories ─────────────────────────────────────────────

    val notifications: NotificationsSettingsRepository
    val workSchedule: WorkScheduleSettingsRepository
    val greeting: GreetingSettingsRepository
    val defaultAgendaView: DefaultAgendaViewSettingsRepository

    // ── Appearance (flat — migrate to AppearanceSettingsRepository in Phase 6) ──

    val darkTheme: Flow<Boolean>
    val accentColor: Flow<String>
    val fontSizeScale: Flow<Float>

    // ── AI (flat — API key is in SecureStoragePort) ───────────────────────────

    // aiProvider, aiModel, aiBaseUrl inherited from SettingsReader
    val aiSystemPrompt: Flow<String>

    // ── Notifications (flat — migrate in Phase 6) ─────────────────────────────

    val notificationsEnabled: Flow<Boolean>
    val notificationSound: Flow<Boolean>
    val notificationVibration: Flow<Boolean>
    val reminderDefault: Flow<ReminderOffset>

    // ── Work Schedule (flat — migrate in Phase 6) ─────────────────────────────

    val workDayStartMinutes: Flow<Int>
    val workDayEndMinutes: Flow<Int>
    val workLunchStartMinutes: Flow<Int>
    val workLunchEndMinutes: Flow<Int>
    val workWeekendSat: Flow<Boolean>
    val workWeekendSun: Flow<Boolean>

    // ── Greetings (flat — migrate in Phase 6) ─────────────────────────────────

    val greetingMorningEnd: Flow<Int>
    val greetingAfternoonEnd: Flow<Int>

    // ── Account (flat) ───────────────────────────────────────────────────────

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
        val DARK_THEME = booleanPreferencesKey(SettingsNamespace.key(SettingsNamespace.APPEARANCE, "dark_theme"))
        val ACCENT_COLOR = stringPreferencesKey(SettingsNamespace.key(SettingsNamespace.APPEARANCE, "accent_color"))
        val FONT_SIZE_SCALE = floatPreferencesKey(SettingsNamespace.key(SettingsNamespace.APPEARANCE, "font_size_scale"))

        // ── AI ─────────────────────────────────────────────────────────────────────
        val AI_PROVIDER = stringPreferencesKey(SettingsNamespace.key(SettingsNamespace.AI, "provider"))
        val AI_MODEL = stringPreferencesKey(SettingsNamespace.key(SettingsNamespace.AI, "model"))
        val AI_BASE_URL = stringPreferencesKey(SettingsNamespace.key(SettingsNamespace.AI, "base_url"))
        val AI_SYSTEM_PROMPT = stringPreferencesKey(SettingsNamespace.key(SettingsNamespace.AI, "system_prompt"))

        // ── Notifications ─────────────────────────────────────────────────────────
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey(SettingsNamespace.key(SettingsNamespace.NOTIFICATIONS, "enabled"))
        val NOTIFICATION_SOUND = booleanPreferencesKey(SettingsNamespace.key(SettingsNamespace.NOTIFICATIONS, "sound"))
        val NOTIFICATION_VIBRATION = booleanPreferencesKey(SettingsNamespace.key(SettingsNamespace.NOTIFICATIONS, "vibration"))
        val REMINDER_DEFAULT = stringPreferencesKey(SettingsNamespace.key(SettingsNamespace.NOTIFICATIONS, "reminder_default"))

        // ── Work Schedule ────────────────────────────────────────────────────────
        val WORK_DAY_START_MINUTES = intPreferencesKey(SettingsNamespace.key(SettingsNamespace.WORK_SCHEDULE, "work_day_start_minutes"))
        val WORK_DAY_END_MINUTES = intPreferencesKey(SettingsNamespace.key(SettingsNamespace.WORK_SCHEDULE, "work_day_end_minutes"))
        val WORK_LUNCH_START_MINUTES = intPreferencesKey(SettingsNamespace.key(SettingsNamespace.WORK_SCHEDULE, "work_lunch_start_minutes"))
        val WORK_LUNCH_END_MINUTES = intPreferencesKey(SettingsNamespace.key(SettingsNamespace.WORK_SCHEDULE, "work_lunch_end_minutes"))
        val WORK_WEEKEND_SAT = booleanPreferencesKey(SettingsNamespace.key(SettingsNamespace.WORK_SCHEDULE, "weekend_sat"))
        val WORK_WEEKEND_SUN = booleanPreferencesKey(SettingsNamespace.key(SettingsNamespace.WORK_SCHEDULE, "weekend_sun"))

        // ── Greeting ─────────────────────────────────────────────────────────────
        val GREETING_MORNING_END = intPreferencesKey(SettingsNamespace.key(SettingsNamespace.GREETING, "morning_end_hour"))
        val GREETING_AFTERNOON_END = intPreferencesKey(SettingsNamespace.key(SettingsNamespace.GREETING, "afternoon_end_hour"))

        // ── Account ─────────────────────────────────────────────────────────────
        val USER_ID = stringPreferencesKey(SettingsNamespace.key(SettingsNamespace.ACCOUNT, "user_id"))

        // ── Agenda ────────────────────────────────────────────────────────────────
        val DEFAULT_SAVED_AGENDA_VIEW_ID = stringPreferencesKey(SettingsNamespace.key(SettingsNamespace.AGENDA, "default_view_id"))
    }

    // ── Per-section repositories ───────────────────────────────────────────

    override val notifications: NotificationsSettingsRepository =
        DataStoreNotificationsSettingsRepository(dataStore)
    override val workSchedule: WorkScheduleSettingsRepository =
        DataStoreWorkScheduleSettingsRepository(dataStore)
    override val greeting: GreetingSettingsRepository =
        DataStoreGreetingSettingsRepository(dataStore)
    override val defaultAgendaView: DefaultAgendaViewSettingsRepository =
        DataStoreDefaultAgendaViewSettingsRepository(dataStore)

    // ── Appearance ─────────────────────────────────────────────────────────────

    override val darkTheme: Flow<Boolean> = dataStore.data.map { it[DARK_THEME] ?: SettingsDefaults.Appearance.DARK_THEME }
    override val accentColor: Flow<String> = dataStore.data.map { it[ACCENT_COLOR] ?: SettingsDefaults.Appearance.ACCENT_COLOR }
    override val fontSizeScale: Flow<Float> = dataStore.data.map { it[FONT_SIZE_SCALE] ?: SettingsDefaults.Appearance.FONT_SIZE_SCALE }

    // ── AI ───────────────────────────────────────────────────────────────────

    override val aiProvider: Flow<String> = dataStore.data.map { it[AI_PROVIDER] ?: SettingsDefaults.Ai.PROVIDER }
    override val aiModel: Flow<String> = dataStore.data.map { it[AI_MODEL] ?: SettingsDefaults.Ai.MODEL }
    override val aiBaseUrl: Flow<String> = dataStore.data.map { it[AI_BASE_URL] ?: SettingsDefaults.Ai.BASE_URL }
    override val aiSystemPrompt: Flow<String> =
        dataStore.data.map { it[AI_SYSTEM_PROMPT] ?: SettingsDefaults.Ai.SYSTEM_PROMPT }

    // ── Notifications ─────────────────────────────────────────────────────────

    override val notificationsEnabled: Flow<Boolean> =
        dataStore.data.map { it[NOTIFICATIONS_ENABLED] ?: SettingsDefaults.Notifications.ENABLED }
    override val notificationSound: Flow<Boolean> =
        dataStore.data.map { it[NOTIFICATION_SOUND] ?: SettingsDefaults.Notifications.SOUND }
    override val notificationVibration: Flow<Boolean> =
        dataStore.data.map { it[NOTIFICATION_VIBRATION] ?: SettingsDefaults.Notifications.VIBRATION }
    override val reminderDefault: Flow<ReminderOffset> = dataStore.data.map {
        val name = it[REMINDER_DEFAULT] ?: SettingsDefaults.Notifications.REMINDER_DEFAULT.name
        runCatching { ReminderOffset.valueOf(name) }.getOrDefault(SettingsDefaults.Notifications.REMINDER_DEFAULT)
    }

    // ── Work Schedule ──────────────────────────────────────────────────────────

    override val workDayStartMinutes: Flow<Int> =
        dataStore.data.map { it[WORK_DAY_START_MINUTES] ?: SettingsDefaults.WorkSchedule.WORK_DAY_START_MINUTES }
    override val workDayEndMinutes: Flow<Int> =
        dataStore.data.map { it[WORK_DAY_END_MINUTES] ?: SettingsDefaults.WorkSchedule.WORK_DAY_END_MINUTES }
    override val workLunchStartMinutes: Flow<Int> =
        dataStore.data.map { it[WORK_LUNCH_START_MINUTES] ?: SettingsDefaults.WorkSchedule.WORK_LUNCH_START_MINUTES }
    override val workLunchEndMinutes: Flow<Int> =
        dataStore.data.map { it[WORK_LUNCH_END_MINUTES] ?: SettingsDefaults.WorkSchedule.WORK_LUNCH_END_MINUTES }
    override val workWeekendSat: Flow<Boolean> =
        dataStore.data.map { it[WORK_WEEKEND_SAT] ?: SettingsDefaults.WorkSchedule.WEEKEND_SAT }
    override val workWeekendSun: Flow<Boolean> =
        dataStore.data.map { it[WORK_WEEKEND_SUN] ?: SettingsDefaults.WorkSchedule.WEEKEND_SUN }

    // ── Greetings ─────────────────────────────────────────────────────────────

    override val greetingMorningEnd: Flow<Int> =
        dataStore.data.map { it[GREETING_MORNING_END] ?: SettingsDefaults.Greeting.MORNING_END_HOUR }
    override val greetingAfternoonEnd: Flow<Int> =
        dataStore.data.map { it[GREETING_AFTERNOON_END] ?: SettingsDefaults.Greeting.AFTERNOON_END_HOUR }

    // ── Account ───────────────────────────────────────────────────────────────

    override val userId: Flow<String> = dataStore.data.map { it[USER_ID] ?: SettingsDefaults.USER_ID }

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
