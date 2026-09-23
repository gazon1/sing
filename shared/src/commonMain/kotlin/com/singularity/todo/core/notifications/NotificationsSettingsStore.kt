package com.singularity.todo.core.notifications

import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Reads and writes notification settings.
 *
 * Contributes [SettingsSection.Notifications] to the unified settings UI.
 * Registration: `single<SettingsContributor> { NotificationsSettingsContributor(get()) }`.
 */
class NotificationsSettingsStore(
    private val notifications: NotificationsSettingsRepository,
) {
    /**
     * Notification settings section — all 4 fields from [NotificationsSettingsRepository].
     */
    fun observe(): Flow<SettingsSection.Notifications> = combine(
        notifications.enabled,
        notifications.sound,
        notifications.vibration,
        notifications.reminderDefault,
    ) { enabled, sound, vibration, reminderDefault ->
        SettingsSection.Notifications(
            enabled = enabled,
            sound = sound,
            vibration = vibration,
            reminderDefault = reminderDefault,
        )
    }

    suspend fun process(intent: SettingsIntent.Notifications) {
        when (intent) {
            is SettingsIntent.Notifications.UpdateEnabled -> notifications.setEnabled(intent.value)
            is SettingsIntent.Notifications.UpdateSound -> notifications.setSound(intent.value)
            is SettingsIntent.Notifications.UpdateVibration -> notifications.setVibration(intent.value)
            is SettingsIntent.Notifications.UpdateReminderDefault -> notifications.setReminderDefault(intent.value)
        }
    }
}
