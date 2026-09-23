package com.singularity.todo.core.notifications

import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import kotlinx.coroutines.flow.Flow

/**
 * Contributes the Notification settings section to the unified settings UI.
 *
 * Registration: `single<SettingsContributor> { NotificationsSettingsContributor(get()) }`.
 */
class NotificationsSettingsContributor(
    private val store: NotificationsSettingsStore,
) : SettingsContributor<SettingsSection.Notifications, SettingsIntent.Notifications> {

    override val section: SettingsSection.Notifications = SettingsSection.Notifications()

    override fun observe(): Flow<SettingsSection.Notifications> = store.observe()

    override suspend fun process(intent: SettingsIntent.Notifications) {
        store.process(intent)
    }
}
