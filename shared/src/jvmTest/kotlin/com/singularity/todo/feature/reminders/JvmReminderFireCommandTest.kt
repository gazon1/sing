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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The command line a `systemd --user` reminder unit invokes.
 *
 * ## Why this is the only part that stays JVM-only
 *
 * The delivery itself moved to [ReminderDelivery] in common code, where Android shares it.
 * What remains here is genuinely Desktop-only: the argv contract between
 * `JvmReminderScheduler.schedule` — which writes the unit's command line — and
 * `desktopApp`'s `main`, which reads it.
 *
 * ## Why it earns its own file
 *
 * No test in the suite executes a real systemd unit, so this parsing is the only thing
 * standing between a changed argv and an 18:00 reminder that silently never arrives. A
 * change here that broke the contract would otherwise be invisible until a user reported it.
 *
 * The matching half — the argv `schedule` emits — is asserted in
 * `JvmReminderSchedulerCapabilityTest`.
 */
@Tag("fast")
class JvmReminderFireCommandTest {

    /** The path systemd executes; `args[0]` in `fun main(args)`. */
    private val launcher = "/usr/bin/singularity-todo"

    /** Records the argv the scheduler handed to systemd, and succeeds. */
    private class RecordingRunner {
        val calls = mutableListOf<List<String>>()

        suspend fun run(argv: List<String>): Int {
            calls += argv
            return 0
        }
    }

    /**
     * The unit runs this app's own launcher with two opaque ids.
     *
     * Pinned because nothing else in the test suite executes a systemd unit: if the shape
     * changed here, the only failure a user would see is a reminder that never arrives.
     *
     * `args[0]` is the path the OS launched, so the command lands at index 1. Written with
     * the program name present on purpose: the version of this test that omitted it passed
     * against a parser reading `args[0]` as the command — which is how a parser that could
     * never match a real invocation shipped green.
     */
    @Test
    fun `a fire request parses into the reminder and user it was asked for`() {
        val parsed = JvmReminderFireCommand.parse(
            arrayOf(launcher, "fire-reminder", "r1", "u1"),
        )

        assertEquals(ReminderId("r1"), parsed?.reminderId)
        assertEquals(UserId("u1"), parsed?.userId)
    }

    /**
     * An ordinary launch must fall through to the GUI, and a malformed invocation must
     * not be mistaken for a fire request that silently does nothing.
     *
     * Returning null rather than throwing is deliberate: every normal launch arrives here
     * with no arguments, and the overwhelmingly common answer is "not a fire request".
     */
    @Test
    fun `anything that is not a well-formed fire request is not a fire request`() {
        assertNull(
            JvmReminderFireCommand.parse(emptyArray()),
            "an ordinary GUI launch",
        )
        assertNull(
            JvmReminderFireCommand.parse(arrayOf("/usr/bin/singularity-todo")),
            "an ordinary GUI launch with the program name",
        )
        assertNull(
            JvmReminderFireCommand.parse(arrayOf(launcher, "fire-reminder")),
            "no ids at all",
        )
        assertNull(
            JvmReminderFireCommand.parse(arrayOf(launcher, "fire-reminder", "r1")),
            "no user id",
        )
        assertNull(
            JvmReminderFireCommand.parse(arrayOf(launcher, "fire-reminder", "r1", "")),
            "a blank user id is not a user",
        )
        assertNull(
            JvmReminderFireCommand.parse(arrayOf(launcher, "something-else", "r1", "u1")),
            "an unknown subcommand is not a fire request",
        )
    }

    /**
     * The round trip the scheduler and the launcher actually perform.
     *
     * Each half is asserted on its own elsewhere, and **neither of those assertions can
     * catch a swap**: the scheduler test builds its expected argv from a `Reminder`, and
     * this file's other tests build their input by hand, so if `id` and `userId` traded
     * places on the way out and on the way back, both files would still be green while
     * every reminder fired as `NotFound`.
     *
     * So this asks the scheduler for the argv it really emits, cuts it at the `--` that
     * ends systemd's option parsing, and feeds the remainder — **including the launcher
     * path**, because `fun main(args)` receives `args[0]` as the program the OS launched —
     * straight to the parser.
     *
     * This is the test that caught the shipped bug. The version of `parse` that read
     * `args[0]` as the command never matched a real invocation, and every test written
     * against it passed, because every one of them passed an array whose first element was
     * the command. Feeding the scheduler's real output in is what made the assumption
     * visible; asserting on a hand-built array is what let it through.
     */
    @Test
    fun `the argv the scheduler emits parses back to the same ids`() = runTest {
        val reminderId = ReminderId.generate()
        val userId = TestUsers.DEFAULT
        val runner = RecordingRunner()
        val scheduler = JvmReminderScheduler(
            clock = FakeClock(),
            reminderRepo = FakeReminderRepository(),
            notifier = FakeNotifier(supported = true),
            sessionBusAddress = null,
            launcherPath = REMINDER_LAUNCHER_PATH,
            runCommand = { argv -> runner.run(argv) },
            userSystemdAvailable = { true },
        )
        val reminder = Reminder(
            id = reminderId,
            taskId = TaskId("t1"),
            userId = userId,
            type = ReminderType.Gentle,
            offsetMinutes = 15,
            fireAt = FakeClock().now().toEpochMilliseconds() + 3_600_000L,
            recurringPattern = null,
        )

        scheduler.schedule(reminder)
        val argv = runner.calls.single()
        val separator = argv.indexOf("--")
        assertTrue(separator > 0, "systemd option parsing must be terminated before the ids: $argv")

        // The launcher receives `argv` including its own path, exactly as `main` gets it.
        val parsed = JvmReminderFireCommand.parse(argv.drop(separator).toTypedArray())

        assertEquals(reminderId, parsed?.reminderId, "the reminder id must survive the round trip")
        assertEquals(userId, parsed?.userId, "the profile id must survive the round trip")
    }
}
