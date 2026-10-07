package com.singularity.todo.feature.reminders

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Re-arming after the user manager restarts.
 *
 * ## What was untested before this file
 *
 * This logic lived in `desktopApp/main.kt` as a private `suspend fun`, which means **no
 * test executed it** — not because it was hard to test, but because it was written where
 * the tests cannot reach. It is the code that decides whether a Desktop reminder works
 * once or works after a reboot, so "it compiles and it reads correctly" was not good
 * enough.
 *
 * ## The property under test
 *
 * [ReminderScheduler.schedule] throws when `systemd-run` fails. That is correct for a
 * single arming — a caller that returns normally believes it armed something. In a loop it
 * is a defect waiting to happen: one uncaught throw and every reminder after it is silently
 * unarmed, which the user discovers at 18:00 when nothing arrives. So the loop continues,
 * and that is what the first test below pins.
 */
@Tag("fast")
class JvmReminderRearmTest {

    private val now = 1_800_000_000_000L

    /** A scheduler that records what it was asked to arm, and fails on chosen ids. */
    private class RecordingScheduler(
        private val supported: Boolean = true,
        private val failing: Set<String> = emptySet(),
    ) : ReminderScheduler {
        val scheduled = mutableListOf<ReminderId>()

        override val isSupported: Boolean get() = supported

        override suspend fun schedule(reminder: Reminder) {
            if (reminder.id.value in failing) {
                throw IllegalStateException("systemd-run exited 1 for ${reminder.id.value}")
            }
            scheduled += reminder.id
        }

        override suspend fun cancel(id: ReminderId, userId: UserId) = Unit

        override suspend fun cancelByTask(taskId: TaskId, userId: UserId) = Unit
    }

    private fun reminder(
        fireAt: Long = now + 3_600_000L,
        index: Int = 0,
    ) = Reminder(
        id = ReminderId("r$index"),
        taskId = TaskId("t$index"),
        userId = TestUsers.DEFAULT,
        type = ReminderType.Gentle,
        offsetMinutes = 15,
        fireAt = fireAt,
        recurringPattern = null,
    )

    /**
     * The load-bearing test.
     *
     * Without the per-arm catch, the exception from `r1` propagates out of the loop and
     * `r2` and `r3` are never attempted — so the user's 19:00 reminder silently does not
     * fire because an unrelated 18:00 one hit a bad unit.
     */
    @Test
    fun `one reminder that fails to arm does not abandon the rest`() = runTest {
        val scheduler = RecordingScheduler(failing = setOf("r1"))
        val seen = mutableListOf<String>()

        val report = JvmReminderRearm.run(
            scheduler = scheduler,
            reminders = listOf(reminder(index = 1), reminder(index = 2), reminder(index = 3)),
            nowEpochMs = now,
            onFailure = { r, _ -> seen += r.id.value },
        )

        assertEquals(listOf("r2", "r3"), scheduler.scheduled.map { it.value })
        assertEquals(listOf("r1"), seen, "the failure must be reported, not swallowed")
        assertEquals(2, report.armed)
        assertEquals(listOf(ReminderId("r1")), report.failed)
    }

    /**
     * A failure is counted and named, never quietly dropped.
     *
     * The "continue past failures" behaviour above would be a defect on its own if it also
     * swallowed the evidence: a batch where every arm failed must be distinguishable from a
     * batch that never ran.
     */
    @Test
    fun `the report names what failed so it can be logged`() = runTest {
        val report = JvmReminderRearm.run(
            scheduler = RecordingScheduler(failing = setOf("r0", "r2")),
            reminders = listOf(reminder(index = 0), reminder(index = 1), reminder(index = 2)),
            nowEpochMs = now,
        )

        assertEquals(1, report.armed)
        assertEquals(2, report.failed.size)
        assertTrue("FAILED" in report.describe(), "the summary must be loud, got: ${report.describe()}")
    }

    /**
     * Nothing is armed on a host that cannot arm.
     *
     * Asking anyway would fill the launch log with failures for an operation the platform
     * says is impossible — and would make a working configuration indistinguishable from a
     * broken one in the only place anyone looks.
     */
    @Test
    fun `an unsupported platform arms nothing and says so`() = runTest {
        val scheduler = RecordingScheduler(supported = false)

        val report = JvmReminderRearm.run(
            scheduler = scheduler,
            reminders = listOf(reminder(index = 0), reminder(index = 1)),
            nowEpochMs = now,
        )

        assertTrue(scheduler.scheduled.isEmpty())
        assertEquals(false, report.supported)
        assertTrue(
            "cannot" in report.describe(),
            "the summary must distinguish 'cannot arm' from 'armed nothing'; got ${report.describe()}",
        )
    }

    /**
     * A reminder whose moment has passed is not armed.
     *
     * Same rule as `schedule` itself, applied before the call rather than inside it, so a
     * bulk pass does not generate a failure for every reminder in the past.
     */
    @Test
    fun `reminders already past due are skipped, not attempted`() = runTest {
        val scheduler = RecordingScheduler()

        val report = JvmReminderRearm.run(
            scheduler = scheduler,
            reminders = listOf(
                reminder(index = 0, fireAt = now - 1),
                reminder(index = 1, fireAt = now),
                reminder(index = 2, fireAt = now + 1),
            ),
            nowEpochMs = now,
        )

        assertEquals(listOf("r2"), scheduler.scheduled.map { it.value })
        assertEquals(2, report.skippedPastDue)
    }

    /** The happy path, so the failure tests above are not the only thing exercised. */
    @Test
    fun `every future reminder is armed`() = runTest {
        val scheduler = RecordingScheduler()

        val report = JvmReminderRearm.run(
            scheduler = scheduler,
            reminders = listOf(reminder(index = 0), reminder(index = 1)),
            nowEpochMs = now,
        )

        assertEquals(2, report.armed)
        assertTrue(report.failed.isEmpty())
        assertTrue(report.supported)
    }

    /**
     * Calling it twice must not stack duplicate timers.
     *
     * It runs on every launch, so idempotency is not a nicety — it is the difference
     * between one timer per reminder and one per launch-until-the-user-manager-dies. The
     * guarantee comes from `systemd-run` refusing to redefine a live unit, which is why
     * this is a documented precondition rather than something this class enforces.
     */
    @Test
    fun `re-arming is safe to repeat because it is a launch-time pass`() = runTest {
        val scheduler = RecordingScheduler()
        val reminders = listOf(reminder(index = 0))

        val first = JvmReminderRearm.run(scheduler, reminders, now)
        val second = JvmReminderRearm.run(scheduler, reminders, now)

        assertEquals(1, first.armed)
        assertEquals(1, second.armed, "a second launch must re-request the arm, not accumulate")
        assertTrue("already past due" in second.describe() || "re-armed 1" in second.describe())
    }
}
