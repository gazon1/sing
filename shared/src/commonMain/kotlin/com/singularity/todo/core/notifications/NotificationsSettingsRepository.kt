package com.singularity.todo.core.notifications

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.core.settings.SettingsDefaults
import com.singularity.todo.core.settings.SettingsNamespace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Contract for notification settings.
 */
interface NotificationsSettingsRepository {

    val enabled: Flow<Boolean>
    val sound: Flow<Boolean>
    val vibration: Flow<Boolean>
    val reminderDefault: Flow<ReminderOffset>

    suspend fun setEnabled(value: Boolean)
    suspend fun setSound(value: Boolean)
    suspend fun setVibration(value: Boolean)
    suspend fun setReminderDefault(value: ReminderOffset)
}

/**
 * Production [NotificationsSettingsRepository] backed by DataStore.
 */
class DataStoreNotificationsSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : NotificationsSettingsRepository {

    companion object {
        val ENABLED = booleanPreferencesKey(SettingsNamespace.key(SettingsNamespace.NOTIFICATIONS, "enabled"))
        val SOUND = booleanPreferencesKey(SettingsNamespace.key(SettingsNamespace.NOTIFICATIONS, "sound"))
        val VIBRATION = booleanPreferencesKey(SettingsNamespace.key(SettingsNamespace.NOTIFICATIONS, "vibration"))
        val REMINDER_DEFAULT = stringPreferencesKey(SettingsNamespace.key(SettingsNamespace.NOTIFICATIONS, "reminder_default"))
    }

    override val enabled: Flow<Boolean> = dataStore.data.map {
        it[ENABLED] ?: SettingsDefaults.Notifications.ENABLED
    }
    override val sound: Flow<Boolean> = dataStore.data.map {
        it[SOUND] ?: SettingsDefaults.Notifications.SOUND
    }
    override val vibration: Flow<Boolean> = dataStore.data.map {
        it[VIBRATION] ?: SettingsDefaults.Notifications.VIBRATION
    }
    override val reminderDefault: Flow<ReminderOffset> = dataStore.data.map {
        val name = it[REMINDER_DEFAULT] ?: SettingsDefaults.Notifications.REMINDER_DEFAULT.name
        runCatching { ReminderOffset.valueOf(name) }.getOrDefault(SettingsDefaults.Notifications.REMINDER_DEFAULT)
    }

    override suspend fun setEnabled(value: Boolean) {
        dataStore.edit { it[ENABLED] = value }
    }

    override suspend fun setSound(value: Boolean) {
        dataStore.edit { it[SOUND] = value }
    }

    override suspend fun setVibration(value: Boolean) {
        dataStore.edit { it[VIBRATION] = value }
    }

    override suspend fun setReminderDefault(value: ReminderOffset) {
        dataStore.edit { it[REMINDER_DEFAULT] = value.name }
    }
}
