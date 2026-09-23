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
import com.singularity.todo.core.schedule.DataStoreGreetingSettingsRepository
import com.singularity.todo.core.schedule.DataStoreWorkScheduleSettingsRepository
import com.singularity.todo.core.schedule.GreetingSettingsRepository
import com.singularity.todo.core.schedule.WorkScheduleSettingsRepository
import com.singularity.todo.feature.agenda.DataStoreDefaultAgendaViewSettingsRepository
import com.singularity.todo.feature.agenda.DefaultAgendaViewSettingsRepository
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
 * their own namespace prefix. Flat getters for AI (provider, model, base URL,
 * system prompt) are preserved because [AiSettingsStore] depends on them.
 * Flat [userId] is preserved because [com.singularity.todo.feature.profile.ProfileAwareCurrentUser]
 * and the one-shot [SettingsDataStoreMigration] depend on it.
 */
interface SettingsRepository : SettingsReader {

    companion object {
        const val DEFAULT_SYSTEM_PROMPT = SettingsDefaults.Ai.SYSTEM_PROMPT
        const val DEFAULT_AI_MODEL = SettingsDefaults.Ai.MODEL
    }

    // ── Per-section repositories ─────────────────────────────────────────────

    val notifications: NotificationsSettingsRepository
    val workSchedule: WorkScheduleSettingsRepository
    val greeting: GreetingSettingsRepository
    val defaultAgendaView: DefaultAgendaViewSettingsRepository

    // ── AI (flat — AiSettingsStore depends on these) ─────────────────────────

    // aiProvider, aiModel, aiBaseUrl inherited from SettingsReader
    val aiSystemPrompt: Flow<String>

    // ── Account (flat — ProfileAwareCurrentUser + migration) ───────────────

    val userId: Flow<String>

    // ── Setters ───────────────────────────────────────────────────────────

    // AI setters — required by AiSettingsStore.process()
    suspend fun setAiProvider(value: String)
    suspend fun setAiModel(value: String)
    suspend fun setAiBaseUrl(value: String)
    suspend fun setAiSystemPrompt(value: String)

    // Account setter
    suspend fun setUserId(value: String)
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

    // ── AI (flat — AiSettingsStore reads via SettingsReader) ───────────────

    override val aiProvider: Flow<String> = dataStore.data.map { it[AI_PROVIDER] ?: SettingsDefaults.Ai.PROVIDER }
    override val aiModel: Flow<String> = dataStore.data.map { it[AI_MODEL] ?: SettingsDefaults.Ai.MODEL }
    override val aiBaseUrl: Flow<String> = dataStore.data.map { it[AI_BASE_URL] ?: SettingsDefaults.Ai.BASE_URL }
    override val aiSystemPrompt: Flow<String> =
        dataStore.data.map { it[AI_SYSTEM_PROMPT] ?: SettingsDefaults.Ai.SYSTEM_PROMPT }

    // ── Account ───────────────────────────────────────────────────────────────

    override val userId: Flow<String> = dataStore.data.map { it[USER_ID] ?: SettingsDefaults.USER_ID }

    // ── Setters ───────────────────────────────────────────────────────────────

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

    override suspend fun setUserId(value: String) {
        dataStore.edit { it[USER_ID] = value }
    }
}
