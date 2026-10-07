package com.singularity.todo.feature.reminders.data

/**
 * The sanctioned cross-profile reads in this codebase — one per entry, and each one a hole
 * in profile isolation.
 *
 * ## Why reads need this as much as writes do
 *
 * [CrossUserWriteRegistry][com.singularity.todo.feature.agenda.data.CrossUserWriteRegistry]
 * already records every place that writes a row belonging to somebody other than the active
 * profile. This is its counterpart for reads, and it exists because the same reasoning
 * applies with the same force: a query that silently ignores `user_id` is invisible in a
 * diff and indistinguishable from a normal one.
 *
 * ## Why there is an entry at all
 *
 * `ReminderDao.watchAllProfiles()` has no `user_id` filter — the only query in
 * `task_reminders` without one. It is used once, to re-arm OS alarms at boot and at
 * launch.
 *
 * The alternative was not "don't do it". It was: arm from the profile-scoped query, and
 * have a reminder for a profile that is not active never fire until its owner switches to
 * it and relaunches the app. The row exists, the UI says it is set, and nothing reminds
 * anyone — the exact defect class this repository keeps having to unpick.
 *
 * Arming an alarm is a device-wide operation being performed by a device-wide event
 * (`BOOT_COMPLETED`, process start). Scoping it to whoever happened to be logged in at
 * that moment is the bug, not the safety property.
 *
 * ## What the entry does not permit
 *
 * Reads, and only for the re-arm path. Nothing writes through it: a fire still deletes by
 * explicit `user_id`, and `assertCanWrite` still guards every write. If this grows a
 * second entry, the gate that watches it should be extended rather than the list relaxed.
 */
object CrossProfileReadRegistry {

    /**
     * The one sanctioned cross-profile read, as `File.method`.
     *
     * `CrossProfileReadRegistryTest` pins the count at one and fails if the named method
     * disappears, so the entry cannot rot into a fiction — the same treatment
     * `CrossUserWriteRegistry` gets, for the same reason.
     */
    const val REMINDER_WATCH_ALL_PROFILES: String = "ReminderDao.watchAllProfiles"

    /** Every sanctioned cross-profile read. Adding one takes responsibility for the hole. */
    val sanctioned: Set<String> = setOf(REMINDER_WATCH_ALL_PROFILES)
}
