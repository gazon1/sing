package com.singularity.todo.feature.reminders

import co.touchlab.kermit.Logger
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.notifications.Notifier
import com.singularity.todo.core.process.Subprocess
import com.singularity.todo.feature.reminders.domain.port.ReminderRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlin.time.Clock
import kotlinx.coroutines.flow.first

/**
 * Desktop [ReminderScheduler] backed by one `systemd --user` transient timer per reminder.
 *
 * ## Why systemd, and why not the `at(1)` backend this class replaced
 *
 * The JVM half of the deleted `NotificationPort` shelled out to `at(1)`, and it is not to
 * be revived: `cancelAll()` ran `atq`/`atrm` across **every** `at` job on the host,
 * including jobs the user had queued outside this app, and `scheduleAt()` fell back to
 * firing immediately when `at` exited non-zero — so an 18:00 reminder fired at once on
 * any host without `atd`. See
 * `docs/decisions/2026-10-06-notification-port-deleted-because-it-cancelled-other-peoples-jobs.md`.
 *
 * `systemd --user` fixes both halves of that. A unit is named, so cancelling is
 * `systemctl --user stop <unit>` against a name this app chose — there is no enumeration
 * step that can touch anything else. And a failed `systemd-run` is reported here as a
 * failure, never papered over with an immediate fire.
 *
 * ## The unit is this app's own launcher
 *
 * `systemd-run` cannot reach the database, and the notification text has to be computed
 * from a fresh read of the task title at fire time (see [ReminderDelivery]). So the unit
 * runs `singularity-todo fire-reminder <id> <userId>` — the packaged launcher, which
 * returns from `main` before touching AWT — rather than a second helper binary. A helper
 * binary would be a reminder that silently does not fire wherever it was not installed.
 *
 * ## What this does *not* survive: logout
 *
 * A transient unit is created in the user manager and **dies with it**. Reboot, logout, or
 * `systemctl --user daemon-reload` all leave the reminder rows in the database with no
 * unit behind them. That is why the desktop entry point re-arms every reminder at launch,
 * mirroring Android's `ACTION_BOOT_COMPLETED` catch-up. This is a real gap against
 * `AlarmManager.setAlarmClock`, which survives all three, and it is stated here rather than
 * discovered later by a user whose 18:00 reminder did not arrive after they rebooted.
 *
 * ## Why `DBUS_SESSION_BUS_ADDRESS` is set explicitly
 *
 * `notify-send` needs the session bus, and a `systemd --user` unit does not inherit one:
 * the user manager's environment is not the graphical session's. Without this the unit
 * runs, succeeds, and displays nothing — the worst possible outcome, because every exit
 * code says the reminder fired. The bus socket is derived from `XDG_RUNTIME_DIR`, which
 * is where it lives by specification.
 */
class JvmReminderScheduler(
    private val clock: Clock,
    private val reminderRepo: ReminderRepository,
    private val notifier: Notifier,
    private val sessionBusAddress: String? = defaultSessionBusAddress(),
    private val launcherPath: String = REMINDER_LAUNCHER_PATH,
    private val runCommand: suspend (List<String>) -> Int = { Subprocess.runQuietly(it) },
    private val userSystemdAvailable: () -> Boolean = ::probeUserSystemd,
) : ReminderScheduler {

    /**
     * Whether this host can actually arm a reminder that will be seen.
     *
     * A **conjunction**, and deliberately so: arming a timer on a host that cannot
     * display a notification produces a reminder that fires invisibly, which is the same
     * class of defect as a reminder that never fires. The two capabilities are checked
     * once, here, so no caller has to know that Desktop's reminder support needs both.
     */
    private val armable: Boolean by lazy { userSystemdAvailable() && notifier.isSupported }

    override val isSupported: Boolean get() = armable

    override suspend fun schedule(reminder: Reminder) {
        if (!armable) throw RemindersUnsupportedException("schedule a reminder")

        // Same guard as `AlarmManagerReminderScheduler.schedule`: a reminder whose time has
        // already passed is not armed, because there is nothing to arm it for. Android
        // returns silently here too — this is not the "silently lies" case the throw
        // exists for, since the caller is not being told a future alarm exists.
        val now = clock.now().toEpochMilliseconds()
        if (reminder.fireAt <= now) {
            logger.d { "Reminder ${reminder.id.value} is already due; not arming" }
            return
        }

        val unit = unitName(reminder.userId, reminder.id)
        // `@<epoch-seconds>`: systemd resolves this against the **realtime** clock, so a
        // machine that suspends wakes up and still fires at the wall-clock time the user
        // chose. A relative `--on-active` timer would count monotonic time and fire late.
        // Second precision is what systemd's calendar syntax offers, and a reminder that
        // is up to a second early is not a defect anyone can perceive.
        val argv = buildList {
            add("systemd-run")
            add("--user")
            add("--unit=$unit")
            add("--on-calendar=@${reminder.fireAt / MILLIS_PER_SECOND}")
            // systemd coalesces timers by default; a reminder is not a poll.
            add("--timer-property=AccuracySec=1s")
            sessionBusAddress?.let { add("--setenv=DBUS_SESSION_BUS_ADDRESS=$it") }
            add("--")
            add(launcherPath)
            add(JvmReminderFireCommand.COMMAND)
            add(reminder.id.value)
            add(reminder.userId.value)
        }

        val exit = runCommand(argv)
        if (exit != 0) {
            // Thrown, not logged. The caller is about to tell the user the reminder is set;
            // a timer that was never created is a lie told to the person relying on it.
            throw IllegalStateException(
                "systemd-run exited $exit arming unit '$unit'. The reminder was NOT " +
                    "scheduled. Check that a systemd user session is available " +
                    "(`systemctl --user is-system-running`).",
            )
        }
        logger.d { "Armed systemd unit $unit for reminder ${reminder.id.value}" }
    }

    /**
     * Stop the timer and its service.
     *
     * Stays silent when the unit does not exist, and — unlike [schedule] — that silence
     * is correct rather than a swallowed failure: the state being asked for ("not armed")
     * is already true. Throwing here would break every cleanup path that runs on task
     * deletion, and would leave rows written by a build where the unit had already been
     * reaped by systemd impossible to tidy up.
     */
    override suspend fun cancel(id: ReminderId, userId: UserId) {
        if (!armable) return
        val unit = unitName(userId, id)
        runCommand(listOf("systemctl", "--user", "stop", "$unit.timer"))
        // The service may already be running even after its timer is stopped — a stop of
        // the timer alone would let a due reminder fire after the user cancelled it.
        runCommand(listOf("systemctl", "--user", "stop", "$unit.service"))
    }

    /** Enumerates via the repository, then cancels each unit. Same shape as Android. */
    override suspend fun cancelByTask(taskId: TaskId, userId: UserId) {
        if (!armable) return
        reminderRepo.watchByTask(taskId).first()
            .filter { it.userId == userId }
            .forEach { cancel(it.id, it.userId) }
    }

    private companion object {
        val logger = Logger.withTag("JvmReminderScheduler")

        const val SYSTEMD_RUN = "systemd-run"

        const val MILLIS_PER_SECOND = 1000L

        /**
         * The session bus socket, or null when the host does not advertise one.
         *
         * Null rather than a guess: a wrong address is worse than no address, because
         * `notify-send` will fail to connect and the unit will exit non-zero on a host that
         * could have displayed the notification.
         */
        fun defaultSessionBusAddress(): String? =
            System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let { "unix:path=$it/bus" }

        /**
         * Whether `systemd-run --user` exists on this host.
         *
         * `--version` prints and exits without contacting a bus, so it cannot hang on a
         * host with no user session — which a real `systemd-run` invocation could.
         *
         * Through [Subprocess] like every other subprocess in this module. This probe once
         * had its own inline `ProcessBuilder`, complete with the early `close()` that kills
         * the child with SIGPIPE and returns 141 — which read as "this host cannot schedule
         * anything", and turned the entire Desktop reminder feature off on a machine that
         * was perfectly capable. See
         * `docs/decisions/2026-10-07-closing-child-streams-sends-sigpipe.md`.
         */
        fun probeUserSystemd(): Boolean =
            Subprocess.runQuietly(listOf(SYSTEMD_RUN, "--user", "--version")) == 0
    }
}

/**
 * Where a `systemd --user` reminder unit finds the launcher.
 *
 * ## Why this is a constant and not a lookup
 *
 * jpackage puts a Deb's launcher on `PATH` under its `packageName`, so this string is
 * derived from `packageName = "singularity-todo"` in `desktopApp/build.gradle.kts` — a
 * different file, in a different Gradle module, that nothing referenced until
 * `JvmReminderSchedulerLauncherPathTest` did. Rename the package and every Desktop
 * reminder stops arming, on every installed machine, with the failure surfacing only when
 * a user goes looking for an 18:00 reminder that never arrived.
 *
 * That test reads this value, so the two cannot drift without a failing build.
 *
 * ## Why it is not resolved at runtime
 *
 * `which singularity-todo` would tolerate a rename by finding whatever is installed — and
 * would also find a *different* binary if the name were ever taken. The point of the
 * constant is that the unit runs **this** app and nothing else, which is the containment
 * property that outlived the deleted `at` backend.
 */
internal const val REMINDER_LAUNCHER_PATH = "/usr/bin/singularity-todo"

/**
 * The systemd unit name for one reminder.
 *
 * Namespaced under `singularity-reminder-` so that every unit this app can stop is one
 * whose name begins with that prefix. That is the whole containment property: cancellation
 * is a targeted `stop` on a name we chose, never an enumeration of the user's units.
 *
 * Sanitised because the parts are value classes over strings and a unit name is parsed by
 * systemd, not passed as an opaque token. A `-` substitution is unambiguous here — the
 * separator is also `-`, and the two id components have fixed lengths in practice.
 */
internal fun unitName(userId: UserId, id: ReminderId): String =
    "singularity-reminder-${sanitize(userId.value)}-${sanitize(id.value)}"

private fun sanitize(raw: String): String = buildString(raw.length) {
    raw.take(MAX_ID_COMPONENT).forEach { c ->
        append(if (c.isLetterOrDigit() || c == '-' || c == '_') c else '-')
    }
}

/** systemd caps a unit name at 255 bytes; the prefix and two components stay well inside it. */
private const val MAX_ID_COMPONENT = 64
