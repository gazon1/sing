package com.singularity.todo.core.notifications

import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import kotlinx.coroutines.flow.Flow

/**
 * Marker interface for the Notifications settings contributor.
 * Used by [com.singularity.todo.feature.settings.SettingsViewModel] to resolve the
 * contributor without type erasure.
 *
 * The existing [NotificationsSettingsContributor] class implements this interface.
 */
interface NotificationsContributor : SettingsContributor<SettingsSection.Notifications, SettingsIntent.Notifications>

/**
 * Contributes the Notification settings section to the unified settings UI.
 *
 * Registration: `single<SettingsContributor> { NotificationsSettingsContributor(get()) }`.
 */
class NotificationsSettingsContributor(
    private val store: NotificationsSettingsStore,
) : NotificationsContributor {

    override val section: SettingsSection.Notifications = SettingsSection.Notifications()

    override fun observe(): Flow<SettingsSection.Notifications> = store.observe()

    override suspend fun process(intent: SettingsIntent.Notifications) {
        store.process(intent)
    }
}
