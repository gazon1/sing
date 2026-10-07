package com.singularity.todo.core.notifications

/**
 * Posts a user-visible notification.
 *
 * ## Why this port is new rather than resurrected
 *
 * The deleted `NotificationPort` is not coming back, and the reason is specific enough
 * that reusing its name would invite the same mistake. Its JVM half shelled out to the
 * `at(1)` daemon: `cancelAll()` ran `atq`/`atrm` across **every** `at` job on the host,
 * including jobs the user had queued outside this app, and `scheduleAt()` fell back to
 * firing immediately whenever `at` exited non-zero — so an 18:00 reminder fired at once
 * on any host without `atd`. See
 * `docs/decisions/2026-10-06-notification-port-deleted-because-it-cancelled-other-peoples-jobs.md`.
 *
 * The shape here is deliberately different: this port **only displays something**. It
 * never schedules, never cancels, and never enumerates system-wide jobs. Scheduling
 * belongs to `ReminderScheduler`; showing a message belongs here. A port that can only
 * show a notification has no way to reach outside the app's own namespace, which is the
 * property whose absence caused the original deletion.
 *
 * ## Why `isSupported`
 *
 * `notify-send` is not present on a headless host, and a desktop session without a
 * notification daemon will silently drop what it is given. A caller is expected to check
 * this the same way it checks `ReminderScheduler.isSupported`, and for the same reason:
 * a notification that never appears is worse than one the user was told to expect.
 */
interface Notifier {

    /**
     * Whether this platform can show a notification to the user.
     *
     * A runtime probe rather than a constant, because the answer depends on the host:
     * a machine with no `notify-send`, or one with no session bus, cannot show one.
     */
    val isSupported: Boolean

    /**
     * Show [title] and [body] to the user.
     *
     * [tag] replaces any earlier notification with the same tag rather than stacking a
     * second copy, so a reminder that fires twice does not appear twice.
     *
     * [viewId] is an opaque deep-link target the notification carries, or null. It is part
     * of the port rather than an Android extra because **both** production callers have it
     * and Android's `post` already took it: `AlarmReceiver.handleReminderFire`, and the
     * Desktop `JvmReminderFire` that a `systemd --user` unit invokes. A port without it
     * would have made the Desktop implementation drop the tap-through on the floor.
     */
    fun post(tag: String, title: String, body: String, viewId: String? = null)
}
