package com.singularity.todo.core.notifications

/**
 * The desktop has no system notification settings to open.
 *
 * The desktop never refuses a notification permission — the JVM permission
 * requester always reports the permission as granted — so the action this port
 * carries is never offered there. The port exists because the seam has to be bound
 * on both platforms, and because "there is nothing to open" is a fact worth stating
 * rather than a stub that pretends.
 */
class JvmNotificationSettingsPort : NotificationSettingsPort {
    override val isSupported: Boolean = false

    override fun open() = Unit
}
