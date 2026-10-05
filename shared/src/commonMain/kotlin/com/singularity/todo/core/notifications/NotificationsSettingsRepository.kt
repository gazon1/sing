package com.singularity.todo.core.notifications

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.core.settings.BaseSettingsRepository
import com.singularity.todo.core.settings.SettingsDefaults
import com.singularity.todo.core.settings.SettingsNamespace
import kotlinx.coroutines.flow.Flow

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
class DataStoreNotificationsSettingsRepository(dataStore: DataStore<Preferences>) :
    BaseSettingsRepository(dataStore),
    NotificationsSettingsRepository {

    private val enabledPref = boolPref(
        nsKey(SettingsNamespace.NOTIFICATIONS, "enabled"),
        SettingsDefaults.Notifications.ENABLED,
    )
    private val soundPref = boolPref(
        nsKey(SettingsNamespace.NOTIFICATIONS, "sound"),
        SettingsDefaults.Notifications.SOUND,
    )
    private val vibrationPref = boolPref(
        nsKey(SettingsNamespace.NOTIFICATIONS, "vibration"),
        SettingsDefaults.Notifications.VIBRATION,
    )
    private val reminderPref = enumPref(
        nsKey(SettingsNamespace.NOTIFICATIONS, "reminder_default"),
        SettingsDefaults.Notifications.REMINDER_DEFAULT,
        ReminderOffset.entries,
    )

    override val enabled: Flow<Boolean> get() = enabledPref.flow
    override val sound: Flow<Boolean> get() = soundPref.flow
    override val vibration: Flow<Boolean> get() = vibrationPref.flow
    override val reminderDefault: Flow<ReminderOffset> get() = reminderPref.flow

    override suspend fun setEnabled(value: Boolean) = enabledPref.set(value)
    override suspend fun setSound(value: Boolean) = soundPref.set(value)
    override suspend fun setVibration(value: Boolean) = vibrationPref.set(value)
    override suspend fun setReminderDefault(value: ReminderOffset) = reminderPref.set(value)
}
