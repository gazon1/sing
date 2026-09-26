package com.singularity.todo.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * One-shot migration from the legacy flat-key DataStore to the new split + namespaced format.
 *
 * ## Before (v0 — flat keys)
 * ```
 * settings.preferences_pb
 *   dark_theme = false
 *   accent_color = "blue"
 *   notifications_enabled = true
 *   ...
 * ```
 *
 * ## After (v1 — split files + namespaced keys)
 * ```
 * user_settings.preferences_pb
 *   appearance.dark_theme = false
 *   appearance.accent_color = "blue"
 *   notifications.enabled = true
 *   ...
 *
 * state.preferences_pb
 *   settings_schema_version = 1
 *   last_migrated_at = <epoch millis>
 * ```
 *
 * Runs exactly once: checks for `settings_schema_version` in [stateDataStore].
 * If present, skips. If absent, reads every key from [legacyDataStore] and
 * writes to the appropriate new store, then deletes the legacy file.
 *
 * ## Key rename map (flat → namespaced)
 * | Flat key               | Namespaced key                        |
 * |------------------------|---------------------------------------|
 * | dark_theme             | appearance.dark_theme                  |
 * | accent_color           | appearance.accent_color                |
 * | font_size_scale        | appearance.font_size_scale             |
 * | ai_provider            | ai.provider                           |
 * | ai_model               | ai.model                             |
 * | ai_base_url            | ai.base_url                          |
 * | ai_system_prompt       | ai.system_prompt                      |
 * | notifications_enabled  | notifications.enabled                 |
 * | notification_sound     | notifications.sound                   |
 * | notification_vibration | notifications.vibration               |
 * | reminder_default       | notifications.reminder_default        |
 * | work_day_start_minutes | schedule.work_day_start_minutes       |
 * | work_day_end_minutes   | schedule.work_day_end_minutes         |
 * | work_lunch_start_minutes | schedule.work_lunch_start_minutes  |
 * | work_lunch_end_minutes | schedule.work_lunch_end_minutes       |
 * | work_weekend_sat       | schedule.weekend_sat                  |
 * | work_weekend_sun       | schedule.weekend_sun                  |
 * | greeting_morning_end    | greeting.morning_end_hour             |
 * | greeting_afternoon_end | greeting.afternoon_end_hour           |
 * | user_id                | account.user_id                       |
 * | default_saved_agenda_view_id | agenda.default_view_id         |
 *
 * ## Notes
 * - The AI API key is NOT migrated here — it lived in SecureStorage even in v0.
 * - `settings.preferences_pb` is deleted after migration; the file may still
 *   exist on disk but will be empty (PlatformModule creates fresh files).
 * - This function is idempotent: calling it twice is safe.
 */
class SettingsDataStoreMigration(
    private val legacyDataStore: DataStore<Preferences>,
    private val userSettingsDataStore: DataStore<Preferences>,
    private val stateDataStore: DataStore<Preferences>,
) {
    companion object {
        val SCHEMA_VERSION_KEY = intPreferencesKey("settings_schema_version")
        val LAST_MIGRATED_AT_KEY = longPreferencesKey("last_migrated_at")
    }

    /**
     * Runs the migration synchronously (blocking).
     * Call from a `runBlocking` context or from `koinBridge { }` in a Koin factory.
     * Returns `true` if migration ran, `false` if it was already done.
     */
    @Suppress("NoRunBlocking") // one-shot DataStore migration at DI startup — no coroutine context yet
    fun runBlocking(): Boolean = runBlocking { run() }

    /**
     * Suspend entry-point that accepts DataStores as parameters.
     * Use this from platform modules where the DataStores are captured from the
     * surrounding Koin DSL scope and passed in — avoiding `get()` calls inside
     * `koinBridge { }` (which has no receiver and cannot resolve Koin extensions).
     */
    suspend fun runWith(
        legacyDataStore: DataStore<Preferences>,
        userSettingsDataStore: DataStore<Preferences>,
        stateDataStore: DataStore<Preferences>,
    ): Boolean {
        val migrator = SettingsDataStoreMigration(legacyDataStore, userSettingsDataStore, stateDataStore)
        return migrator.run()
    }

    suspend fun run(): Boolean {
        // Already migrated?
        val state = stateDataStore.data.first()
        if (SCHEMA_VERSION_KEY in state) return false

        val legacy = legacyDataStore.data.first()
        if (legacy.asMap().isEmpty()) {
            // Fresh install — nothing to migrate, just write schema version
            stateDataStore.edit {
                it[SCHEMA_VERSION_KEY] = SettingsDefaults.SCHEMA_VERSION
                it[LAST_MIGRATED_AT_KEY] = System.currentTimeMillis()
            }
            return false
        }

        // Map legacy keys → namespaced, write to user_settings
        userSettingsDataStore.edit { target ->
            legacy[DARK_THEME_LEGACY]?.let { target[DataStoreSettingsRepository.DARK_THEME] = it }
            legacy[ACCENT_COLOR_LEGACY]?.let { target[DataStoreSettingsRepository.ACCENT_COLOR] = it as String }
            legacy[FONT_SIZE_SCALE_LEGACY]?.let { target[DataStoreSettingsRepository.FONT_SIZE_SCALE] = it }
            legacy[AI_PROVIDER_LEGACY]?.let { target[DataStoreSettingsRepository.AI_PROVIDER] = it as String }
            legacy[AI_MODEL_LEGACY]?.let { target[DataStoreSettingsRepository.AI_MODEL] = it as String }
            legacy[AI_BASE_URL_LEGACY]?.let { target[DataStoreSettingsRepository.AI_BASE_URL] = it as String }
            legacy[AI_SYSTEM_PROMPT_LEGACY]?.let { target[DataStoreSettingsRepository.AI_SYSTEM_PROMPT] = it as String }
            legacy[NOTIFICATIONS_ENABLED_LEGACY]?.let { target[DataStoreSettingsRepository.NOTIFICATIONS_ENABLED] = it }
            legacy[NOTIFICATION_SOUND_LEGACY]?.let { target[DataStoreSettingsRepository.NOTIFICATION_SOUND] = it }
            legacy[NOTIFICATION_VIBRATION_LEGACY]?.let {
                target[DataStoreSettingsRepository.NOTIFICATION_VIBRATION] = it
            }
            legacy[REMINDER_DEFAULT_LEGACY]?.let { target[DataStoreSettingsRepository.REMINDER_DEFAULT] = it as String }
            legacy[WORK_DAY_START_MINUTES_LEGACY]?.let {
                target[DataStoreSettingsRepository.WORK_DAY_START_MINUTES] = it
            }
            legacy[WORK_DAY_END_MINUTES_LEGACY]?.let { target[DataStoreSettingsRepository.WORK_DAY_END_MINUTES] = it }
            legacy[WORK_LUNCH_START_MINUTES_LEGACY]?.let {
                target[DataStoreSettingsRepository.WORK_LUNCH_START_MINUTES] =
                    it
            }
            legacy[WORK_LUNCH_END_MINUTES_LEGACY]?.let {
                target[DataStoreSettingsRepository.WORK_LUNCH_END_MINUTES] = it
            }
            legacy[WORK_WEEKEND_SAT_LEGACY]?.let { target[DataStoreSettingsRepository.WORK_WEEKEND_SAT] = it }
            legacy[WORK_WEEKEND_SUN_LEGACY]?.let { target[DataStoreSettingsRepository.WORK_WEEKEND_SUN] = it }
            legacy[GREETING_MORNING_END_LEGACY]?.let { target[DataStoreSettingsRepository.GREETING_MORNING_END] = it }
            legacy[GREETING_AFTERNOON_END_LEGACY]?.let {
                target[DataStoreSettingsRepository.GREETING_AFTERNOON_END] = it
            }
            legacy[USER_ID_LEGACY]?.let { target[DataStoreSettingsRepository.USER_ID] = it as String }
            legacy[DEFAULT_SAVED_AGENDA_VIEW_ID_LEGACY]?.let {
                target[DataStoreSettingsRepository.DEFAULT_SAVED_AGENDA_VIEW_ID] =
                    it as String
            }
        }

        // Mark migration done
        stateDataStore.edit {
            it[SCHEMA_VERSION_KEY] = SettingsDefaults.SCHEMA_VERSION
            it[LAST_MIGRATED_AT_KEY] = System.currentTimeMillis()
        }

        return true
    }
}

// ─── Legacy key definitions (flat names, pre-migration) ──────────────────────

private val DARK_THEME_LEGACY = booleanPreferencesKey("dark_theme")
private val ACCENT_COLOR_LEGACY = stringPreferencesKey("accent_color")
private val FONT_SIZE_SCALE_LEGACY = floatPreferencesKey("font_size_scale")
private val AI_PROVIDER_LEGACY = stringPreferencesKey("ai_provider")
private val AI_MODEL_LEGACY = stringPreferencesKey("ai_model")
private val AI_BASE_URL_LEGACY = stringPreferencesKey("ai_base_url")
private val AI_SYSTEM_PROMPT_LEGACY = stringPreferencesKey("ai_system_prompt")
private val NOTIFICATIONS_ENABLED_LEGACY = booleanPreferencesKey("notifications_enabled")
private val NOTIFICATION_SOUND_LEGACY = booleanPreferencesKey("notification_sound")
private val NOTIFICATION_VIBRATION_LEGACY = booleanPreferencesKey("notification_vibration")
private val REMINDER_DEFAULT_LEGACY = stringPreferencesKey("reminder_default")
private val WORK_DAY_START_MINUTES_LEGACY = intPreferencesKey("work_day_start_minutes")
private val WORK_DAY_END_MINUTES_LEGACY = intPreferencesKey("work_day_end_minutes")
private val WORK_LUNCH_START_MINUTES_LEGACY = intPreferencesKey("work_lunch_start_minutes")
private val WORK_LUNCH_END_MINUTES_LEGACY = intPreferencesKey("work_lunch_end_minutes")
private val WORK_WEEKEND_SAT_LEGACY = booleanPreferencesKey("work_weekend_sat")
private val WORK_WEEKEND_SUN_LEGACY = booleanPreferencesKey("work_weekend_sun")
private val GREETING_MORNING_END_LEGACY = intPreferencesKey("greeting_morning_end")
private val GREETING_AFTERNOON_END_LEGACY = intPreferencesKey("greeting_afternoon_end")
private val USER_ID_LEGACY = stringPreferencesKey("user_id")
private val DEFAULT_SAVED_AGENDA_VIEW_ID_LEGACY = stringPreferencesKey("default_saved_agenda_view_id")
