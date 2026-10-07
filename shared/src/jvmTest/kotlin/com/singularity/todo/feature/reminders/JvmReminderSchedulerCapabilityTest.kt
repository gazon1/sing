package com.singularity.todo.feature.reminders

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotifier
import com.singularity.todo.test.fakes.FakeReminderRepository
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Desktop reminder contract, on a host that has `systemd --user`.
 *
 * ## What changed and what did not
 *
 * The scheduler used to be three no-ops. It is now a real `systemd --user` backend, and
 * this file moved with it — but the *refusal* half of the contract is unchanged and is
 * the part worth reading twice, because it is now conditional rather than constant.
 *
 * `isSupported` was `false` on every Desktop, unconditionally. It is now the conjunction
 * of two host facts: a `systemd --user` session exists, and `notify-send` can display
 * something. A container, a CI runner, or an SSH session satisfies neither. If that
 * conjunction were ever collapsed to a constant, this file would be asserting a lie.
 *
 * ## The defect this still pins
 *
 * A caller that writes a reminder row and arms no timer has told the user a lie that the
 * UI renders as a working reminder forever. Two independent guards prevent it:
 * [isSupported], which callers are meant to read before persisting, and the throw from
 * [schedule], which they cannot miss silently if they forget. Both are tested here, and
 * the asymmetry between arming and cancelling is tested explicitly, because it is the
 * part most likely to be "tidied up" by someone assuming the three methods should match.
 */
@Tag("fast")
class JvmReminderSchedulerCapabilityTest {

    private val clock = FakeClock()

    /** Records the argv it was handed; returns the exit code the scenario needs. */
    private class RecordingRunner(private val exit: Int = 0) {
        val calls = mutableListOf<List<String>>()

        suspend fun run(argv: List<String>): Int {
            calls += argv
            return exit
        }
    }

    private fun scheduler(
        runner: RecordingRunner = RecordingRunner(),
        systemdPresent: Boolean = true,
        notifier: FakeNotifier = FakeNotifier(supported = true),
        repo: FakeReminderRepository = FakeReminderRepository(),
    ) = JvmReminderScheduler(
        clock = clock,
        reminderRepo = repo,
        notifier = notifier,
        sessionBusAddress = "unix:path=/run/user/1000/bus",
        launcherPath = "/usr/bin/singularity-todo",
        runCommand = { argv -> runner.run(argv) },
        userSystemdAvailable = { systemdPresent },
    )

    private fun reminder(
        fireAt: Long = clock.now().toEpochMilliseconds() + 3600_000L,
        taskId: TaskId = TaskId("t1"),
        userId: UserId = UserId("u1"),
    ) = Reminder(
        id = ReminderId.generate(),
        taskId = taskId,
        userId = userId,
        type = ReminderType.Gentle,
        offsetMinutes = 15,
        fireAt = fireAt,
        recurringPattern = null,
    )

    // ─── The conjunction ───────────────────────────────────────────────────────

    /**
     * Both halves are load-bearing.
     *
     * systemd alone would arm a timer whose `notify-send` finds no bus and exits
     * non-zero — a reminder that fires and displays nothing, which is worse than one that
     * never fires because the user at least knows it is missing. `notify-send` alone
     * cannot arm anything at all.
     */
    @Test
    fun `support requires both systemd and a working notifier`() {
        assertTrue(
            scheduler(systemdPresent = true, notifier = FakeNotifier(supported = true)).isSupported,
            "a normal desktop session must arm reminders",
        )
        assertFalse(
            scheduler(systemdPresent = false, notifier = FakeNotifier(supported = true)).isSupported,
            "without a systemd user session there is nothing to arm",
        )
        assertFalse(
            scheduler(systemdPresent = true, notifier = FakeNotifier(supported = false)).isSupported,
            "arming a timer nothing can display is the defect, not the fix",
        )
    }

    // ─── Refusal ───────────────────────────────────────────────────────────────

    /**
     * The declared flag is what callers are meant to read; the throw is the backstop for
     * the ones that forget.
     *
     * A host that cannot arm anything must still throw rather than return normally: a
     * caller that returns successfully believes it has scheduled something, and the user
     * gets a reminder that never fires with no indication anything is wrong.
     */
    @Test
    fun `schedule refuses rather than silently doing nothing`() = runTest {
        val runner = RecordingRunner()
        val scheduler = scheduler(runner = runner, systemdPresent = false)

        val thrown = assertFailsWith<RemindersUnsupportedException> {
            scheduler.schedule(reminder())
        }

        assertEquals(
            "schedule a reminder",
            thrown.operation,
            "the exception must name the operation, so a caller can tell which seam was inert",
        )
        assertTrue(
            runner.calls.isEmpty(),
            "an unsupported host must not shell out and hope",
        )
    }

    /**
     * A `systemd-run` that fails is a reminder that was never armed.
     *
     * This is the case a naive implementation gets wrong in the most damaging direction:
     * it logs the non-zero exit and returns, and the caller reports success to a user who
     * is relying on the alarm. It is also the failure the deleted `at` backend used to
     * paper over by firing the reminder *immediately* instead.
     */
    @Test
    fun `a failed systemd-run throws rather than reporting a reminder as armed`() = runTest {
        val scheduler = scheduler(runner = RecordingRunner(exit = 1))

        val thrown = assertFailsWith<IllegalStateException> { scheduler.schedule(reminder()) }

        assertTrue(
            thrown.message?.contains("was NOT scheduled") == true,
            "the message must say the reminder is not scheduled, got: ${thrown.message}",
        )
    }

    /**
     * A reminder whose time has passed is not armed, and that is not a failure.
     *
     * Same rule and same silence as `AlarmManagerReminderScheduler`: there is no future
     * moment to arm a timer for. The caller is not being told an alarm exists, so this is
     * not the "silently lies" case the throw guards.
     */
    @Test
    fun `a reminder already due is not armed`() = runTest {
        val runner = RecordingRunner()
        val scheduler = scheduler(runner = runner)

        scheduler.schedule(reminder(fireAt = clock.now().toEpochMilliseconds() - 1))

        assertTrue(runner.calls.isEmpty(), "nothing to arm, so nothing may be run")
    }

    // ─── Cancelling stays silent ───────────────────────────────────────────────

    /**
     * Cancelling is deliberately *not* symmetric with scheduling.
     *
     * The state being asked for — "not armed" — is already true, so there is nothing to
     * report. Throwing here would break every cleanup path that runs on task deletion,
     * due-date removal, and Google import, and would leave a user unable to remove rows
     * written by a build whose units systemd has since reaped.
     */
    @Test
    fun `cancel and cancelByTask stay silent when nothing is armed`() = runTest {
        val runner = RecordingRunner(exit = 1)
        val scheduler = scheduler(runner = runner, systemdPresent = false)
        val reminder = reminder()

        scheduler.cancel(reminder.id, reminder.userId)
        scheduler.cancelByTask(reminder.taskId, reminder.userId)

        assertTrue(
            runner.calls.isEmpty(),
            "an unsupported host must not shell out at all, not even to stop nothing",
        )
        assertFalse(scheduler.isSupported, "cleanup must not change what the platform supports")
    }

    /**
     * On a supported host, cancelling targets **named** units.
     *
     * This is the containment property the deleted `at` backend lacked: it ran `atrm`
     * across every job on the host. Here every command names a unit this app created, so
     * a user's own systemd timers are structurally unreachable.
     */
    @Test
    fun `cancel stops only this app's own named units`() = runTest {
        val runner = RecordingRunner()
        val scheduler = scheduler(runner = runner)
        val reminder = reminder()

        scheduler.cancel(reminder.id, reminder.userId)

        val unit = unitName(reminder.userId, reminder.id)
        assertEquals(
            listOf(
                listOf("systemctl", "--user", "stop", "$unit.timer"),
                listOf("systemctl", "--user", "stop", "$unit.service"),
            ),
            runner.calls,
            "cancellation must be a targeted stop on names we chose, never an enumeration",
        )
        assertTrue(
            runner.calls.all { it.none { arg -> arg == "list-units" || arg == "list-timers" } },
            "enumerating system units is the failure that got at(1) deleted",
        )
    }

    // ─── Arming ────────────────────────────────────────────────────────────────

    /**
     * The argv is the whole contract with systemd, so it is asserted as a whole.
     *
     * Three details are load-bearing and each would be invisible to a smoke test:
     * `--` ends option parsing so a reminder id can never be read as a flag;
     * `DBUS_SESSION_BUS_ADDRESS` is set because a user unit does not inherit the graphical
     * session's bus and `notify-send` would otherwise exit non-zero having shown nothing;
     * `--on-calendar=@` is wall-clock, so a machine that suspends still fires on time.
     */
    @Test
    fun `arming produces one transient timer that runs this app's own launcher`() = runTest {
        val runner = RecordingRunner()
        val scheduler = scheduler(runner = runner)
        val reminder = reminder(fireAt = 1_800_000_000_000L)

        scheduler.schedule(reminder)

        assertEquals(
            listOf(
                listOf(
                    "systemd-run",
                    "--user",
                    "--unit=${unitName(reminder.userId, reminder.id)}",
                    "--on-calendar=@1800000000",
                    "--timer-property=AccuracySec=1s",
                    "--setenv=DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus",
                    "--",
                    "/usr/bin/singularity-todo",
                    "fire-reminder",
                    reminder.id.value,
                    reminder.userId.value,
                ),
            ),
            runner.calls,
        )
    }

    /**
     * The values the unit receives come from the database, and a shell would give them a
     * way out. The same rule `JvmNotifier.post` holds for the title.
     */
    @Test
    fun `ids stay separate argv elements and can never be read as flags`() = runTest {
        val runner = RecordingRunner()
        val scheduler = scheduler(runner = runner)
        val hostile = reminder(userId = UserId("u1; rm -rf ~"))

        scheduler.schedule(hostile)

        val argv = runner.calls.single()
        assertTrue("--" in argv, "option parsing must be terminated before the ids")
        assertTrue(
            hostile.userId.value in argv,
            "the hostile id must arrive as one opaque element, got $argv",
        )
    }

    /**
     * `cancelByTask` reaches every reminder on that task, through the repository.
     *
     * Pinned because the enumeration moved: there is no system-wide job list to scan
     * (that was the `at` bug), so this depends entirely on the repository returning them.
     */
    @Test
    fun `cancelByTask cancels every reminder the repository reports for the task`() = runTest {
        val repo = FakeReminderRepository()
        val taskId = TaskId("t-shared")
        // `TestUsers.DEFAULT`, not an arbitrary id: `watchByTask` is scoped to the active
        // user, so a reminder seeded under a different id is invisible by design.
        val mine = reminder(taskId = taskId, userId = TestUsers.DEFAULT)
        repo.seed(mine)
        val runner = RecordingRunner()
        val scheduler = scheduler(runner = runner, repo = repo)

        scheduler.cancelByTask(taskId, mine.userId)

        assertTrue(
            runner.calls.isNotEmpty(),
            "the seeded reminder should have produced a stop",
        )
        assertTrue(
            runner.calls.all { call -> call.any { it.startsWith(unitName(mine.userId, mine.id)) } },
            "every stop must name this reminder's own unit; got ${runner.calls}",
        )
    }

    /**
     * Reminders for another profile are left alone.
     *
     * Cross-profile safety is the reason every alarm key is scoped by `userId`, and it
     * applies to cancellation exactly as it does to arming.
     */
    @Test
    fun `cancelByTask ignores another profile's reminders`() = runTest {
        val repo = FakeReminderRepository()
        val taskId = TaskId("t-shared")
        val mine = reminder(taskId = taskId, userId = TestUsers.DEFAULT)
        repo.seed(mine)
        val runner = RecordingRunner()
        val scheduler = scheduler(runner = runner, repo = repo)

        scheduler.cancelByTask(taskId, UserId("someone-else"))

        assertTrue(
            runner.calls.isEmpty(),
            "another profile's unit must not be stopped; got ${runner.calls}",
        )
    }

    // A project reminder has no scheduler on *any* platform, so there is deliberately
    // no test here for it: the project UI is gated by the separate
    // `PROJECT_REMINDERS_SUPPORTED` constant rather than by this scheduler. The two
    // facts are easy to conflate, and the KDoc this file replaces conflated them.
}
