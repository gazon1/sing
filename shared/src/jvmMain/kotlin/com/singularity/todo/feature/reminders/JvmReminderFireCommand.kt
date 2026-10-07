package com.singularity.todo.feature.reminders

import com.singularity.todo.core.ids.UserId

/**
 * The command-line form of [ReminderDelivery], parsed without starting anything.
 *
 * Split out from the desktop entry point so the parsing is testable without a Koin graph,
 * a database, or a display — and so the argv contract the systemd unit depends on has a
 * test of its own rather than being asserted only through a manual `systemd-run`.
 *
 * ## Shape
 *
 * ```
 * singularity-todo fire-reminder <reminderId> <userId>
 * ```
 *
 * The launcher binary is the packaged Desktop app itself, not a second artifact: a
 * reminder that needs a helper binary installed next to it is a reminder that silently
 * does not fire on every machine where the helper was forgotten. `main` returns before
 * `singleWindowApplication`, so no window or AWT toolkit is initialised.
 */
object JvmReminderFireCommand {

    const val COMMAND = "fire-reminder"

    /** A parsed, validated `fire-reminder` invocation. */
    data class Request(val reminderId: ReminderId, val userId: UserId)

    /**
     * Parse [args] exactly as `fun main(args: Array<String>)` receives them, or return
     * null when [args] is not a fire request.
     *
     * ## `args[0]` is the program name, not the command
     *
     * This is the bug this method had, and it shipped: it read `args[0]` as the command,
     * so it never matched anything.
     *
     * A JVM entry point's `args[0]` is the **path the OS used to launch the process**. For
     * the unit systemd runs, that is `/usr/bin/singularity-todo`, and `fire-reminder`
     * arrives at index **1**. Every reminder therefore parsed as "not a fire request",
     * `main` fell through to the GUI branch, a window was created and destroyed, and the
     * notification never appeared — with `systemd-run` reporting success, because the unit
     * exited 0.
     *
     * Nothing about that failure points at the argv contract. A user sees a reminder that
     * fires exactly once (before WS4's re-arm landed) or not at all, and the log shows a
     * clean exit.
     *
     * So the command is located rather than assumed to be first. The alternative — skipping
     * index 0 — is correct too, and less forgiving: it makes every caller reconstruct
     * that `args` here is the raw argv rather than the arguments after the program name.
     *
     * Null rather than an exception, because "not a fire request" is the overwhelmingly
     * common case: every ordinary launch arrives here too, with no arguments at all.
     */
    fun parse(args: Array<String>): Request? {
        val commandIndex = args.indexOfFirst { it == COMMAND }
        if (commandIndex < 0) return null
        val reminderId = args.getOrNull(commandIndex + 1)?.takeIf { it.isNotBlank() } ?: return null
        val userId = args.getOrNull(commandIndex + 2)?.takeIf { it.isNotBlank() } ?: return null
        return Request(ReminderId(reminderId), UserId(userId))
    }
}
