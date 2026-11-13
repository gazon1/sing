package com.singularity.todo.core.notifications

/**
 * Opens the platform's notification settings.
 *
 * The app can be refused the notification permission by the system at any time, and
 * a refused permission cannot be granted from inside the app — only the platform's
 * own settings screen can undo it. So the way out of "notifications are off" is a
 * platform fact, and it arrives here as a port.
 *
 * The desktop has no such screen: its notification settings live in the app's own
 * settings screen, and the system never refuses anything. The JVM implementation
 * therefore reports [isSupported] `false` rather than pretending to open something
 * that does not exist.
 */
interface NotificationSettingsPort {
    /**
     * Whether this platform has a notification settings screen to open.
     *
     * The UI reads it before offering the action: a settings button that opens
     * nothing is worse than no button.
     */
    val isSupported: Boolean

    /**
     * Opens the notification settings.
     *
     * A no-op where [isSupported] is false.
     */
    fun open()
}
