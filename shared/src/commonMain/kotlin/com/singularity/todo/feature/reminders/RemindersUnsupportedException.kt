package com.singularity.todo.feature.reminders

/**
 * Thrown when a caller asks a platform that cannot arm alarms to do so.
 *
 * ## Why this exists rather than a silent no-op
 *
 * `JvmReminderScheduler` used to implement all three port methods as empty bodies.
 * That was a *silent* failure: a caller could persist a reminder, see its `schedule()`
 * call return normally, and ship a reminder that could never fire. The UI reported
 * success because there was nothing to report — the call had not failed, it had done
 * nothing.
 *
 * A no-op is the worst possible contract for a method named `schedule`. It converts a
 * missing capability into a successful-looking call, so every caller has to remember to
 * check [ReminderScheduler.isSupported] first. That is a rule every callsite can miss,
 * and the ones that do miss it fail silently at fire time, in production, weeks later.
 *
 * Throwing moves the failure to the callsite that forgot the check, at the moment it
 * forgets it. The existing `isSupported` guard in
 * [com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskRemindersSlot]
 * still runs first — this exception is the backstop for the *next* caller, not a
 * replacement for it.
 *
 * ## Not an `Error`
 *
 * `error("…")` would produce `IllegalStateException`, which callers already catch
 * blanket-style via `runCatching` and would report as a generic failure. A distinct type
 * is greppable, survives a `runCatchingResult` wrapper as `exceptionOrNull() is
 * RemindersUnsupportedException`, and lets a test assert the *reason* rather than merely
 * that something threw.
 */
class RemindersUnsupportedException(
    /** The platform or operation name, so the message says which seam was inert. */
    val operation: String,
) : IllegalStateException(
        "Cannot $operation: this platform has no alarm scheduler, so a reminder scheduled " +
            "here would never fire. Check ReminderScheduler.isSupported before persisting.",
    )
