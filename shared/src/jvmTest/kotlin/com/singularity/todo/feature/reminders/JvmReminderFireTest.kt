package com.singularity.todo.feature.reminders

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeNotifier
import com.singularity.todo.test.fakes.FakeReminderRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.TestUsers
import com.singularity.todo.test.fakes.testTask
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Firing one Desktop reminder, and the argv contract the systemd unit depends on.
 *
 * ## Why this path exists at all
 *
 * `systemd-run` cannot reach the database. The obvious shortcut — bake the task title into
 * the unit and let `notify-send` print it — is two lines of shell, and it is wrong twice
 * over: the title goes stale when the user renames the task between arming and firing
 * (the exact bug [ReminderFireLogic] was written to prevent), and it puts user-authored
 * text on a command line whose escaping rules belong to systemd.
 *
 * So the unit runs this app's launcher instead, and this file pins what that launcher does.
 * The behaviour is deliberately identical to `AlarmReceiver.handleReminderFire`: same row
 * lookup, same fresh title read, same tag, same delete-one-shots decision. Two platforms
 * whose reminder semantics drift apart is a bug waiting for a user to report it as one.
 */
@Tag("fast")
class JvmReminderFireTest {

    private fun reminder(
        taskId: TaskId = TaskId("t1"),
        recurring: String? = null,
    ) = Reminder(
        id = ReminderId.generate(),
        taskId = taskId,
        userId = TestUsers.DEFAULT,
        type = ReminderType.Gentle,
        offsetMinutes = 15,
        fireAt = 1_800_000_000_000L,
        recurringPattern = recurring,
    )

    // ─── The argv contract ─────────────────────────────────────────────────────

    /**
     * The unit runs this app's own launcher with two opaque ids.
     *
     * Pinned because nothing else in the test suite executes a systemd unit: if the shape
     * changed here, the only failure a user would see is an 18:00 reminder that silently
     * never arrives.
     */
    @Test
    fun `a fire request parses into the reminder and user it was asked for`() {
        val parsed = JvmReminderFireCommand.parse(arrayOf("fire-reminder", "r1", "u1"))

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
        assertNull(JvmReminderFireCommand.parse(emptyArray()), "an ordinary GUI launch")
        assertNull(JvmReminderFireCommand.parse(arrayOf("fire-reminder")), "no ids at all")
        assertNull(JvmReminderFireCommand.parse(arrayOf("fire-reminder", "r1")), "no user id")
        assertNull(
            JvmReminderFireCommand.parse(arrayOf("fire-reminder", "r1", "")),
            "a blank user id is not a user",
        )
        assertNull(JvmReminderFireCommand.parse(arrayOf("something-else", "r1", "u1")))
    }

    // ─── Firing ────────────────────────────────────────────────────────────────

    /**
     * The title is read now, not remembered from arm time.
     *
     * The whole reason the launcher exists rather than a shell one-liner. Renaming a task
     * after arming the reminder must change what the notification says.
     */
    @Test
    fun `the notification carries the task title as it is at fire time`() = runTest {
        val reminderRepo = FakeReminderRepository()
        val taskRepo = FakeTaskRepository()
        val notifier = FakeNotifier()
        val reminder = reminder()
        reminderRepo.seed(reminder)
        taskRepo.seed(
            testTask(id = reminder.taskId, title = "Renamed after arming"),
        )

        val outcome = JvmReminderFire.fire(
            reminderRepo,
            taskRepo,
            notifier,
            reminder.id,
            reminder.userId,
        )

        assertEquals(JvmReminderFire.Outcome.Posted, outcome)
        assertEquals(
            "Reminder: Renamed after arming",
            notifier.posts.single().title,
            "a stale title here is the defect the launcher path exists to avoid",
        )
    }

    /**
     * A deleted task still produces a notification, with the generic title.
     *
     * `ReminderFireLogic` decides this, and it is deliberate: the user asked to be
     * reminded about something, and "Task Reminder" is more use than silence — the row can
     * still be cleaned up by the recurring re-arm.
     */
    @Test
    fun `a reminder whose task is gone falls back to the generic title`() = runTest {
        val reminderRepo = FakeReminderRepository()
        val notifier = FakeNotifier()
        val reminder = reminder()
        reminderRepo.seed(reminder)

        JvmReminderFire.fire(
            reminderRepo,
            FakeTaskRepository(),
            notifier,
            reminder.id,
            reminder.userId,
        )

        assertEquals("Task Reminder", notifier.posts.single().title)
    }

    /** The tag is the same one Android uses, so a cross-platform view cannot double-post. */
    @Test
    fun `the notification is tagged the way every other reminder is`() = runTest {
        val reminderRepo = FakeReminderRepository()
        val notifier = FakeNotifier()
        val reminder = reminder()
        reminderRepo.seed(reminder)

        JvmReminderFire.fire(
            reminderRepo,
            FakeTaskRepository(),
            notifier,
            reminder.id,
            reminder.userId,
        )

        assertEquals(
            "reminder:${reminder.userId.value}:${reminder.id.value}",
            notifier.posts.single().tag,
        )
    }

    /**
     * A one-shot is retired; a recurring one is kept for its caller to re-arm.
     *
     * Retiring the one-shot is what stops a notification reappearing on every launch: the
     * desktop entry point re-arms everything in the database, so a row left behind would be
     * armed again forever.
     */
    @Test
    fun `a one-shot reminder is deleted after it fires`() = runTest {
        val reminderRepo = FakeReminderRepository()
        val notifier = FakeNotifier()
        val reminder = reminder(recurring = null)
        reminderRepo.seed(reminder)

        JvmReminderFire.fire(
            reminderRepo,
            FakeTaskRepository(),
            notifier,
            reminder.id,
            reminder.userId,
        )

        assertNull(
            reminderRepo.reminders.value[reminder.id.value],
            "a one-shot that is not deleted is re-armed on every launch, forever",
        )
    }

    @Test
    fun `a recurring reminder survives its fire`() = runTest {
        val reminderRepo = FakeReminderRepository()
        val notifier = FakeNotifier()
        val reminder = reminder(recurring = "FREQ=DAILY")
        reminderRepo.seed(reminder)

        JvmReminderFire.fire(
            reminderRepo,
            FakeTaskRepository(),
            notifier,
            reminder.id,
            reminder.userId,
        )

        assertTrue(
            reminderRepo.reminders.value.containsKey(reminder.id.value),
            "the caller re-arms a recurring reminder; deleting it here would end the series",
        )
    }

    /**
     * A host that cannot display anything says so instead of pretending.
     *
     * `NoNotifier` is a distinct outcome rather than a silent `Posted`, because a reminder
     * that was armed and then could not be shown is the one case where the user is worse
     * off than if it had never been created — and the unit's log is the only place that
     * fact will ever appear.
     */
    @Test
    fun `nothing is posted and the row is kept when the host cannot display`() = runTest {
        val reminderRepo = FakeReminderRepository()
        val notifier = FakeNotifier(supported = false)
        val reminder = reminder()
        reminderRepo.seed(reminder)

        val outcome = JvmReminderFire.fire(
            reminderRepo,
            FakeTaskRepository(),
            notifier,
            reminder.id,
            reminder.userId,
        )

        assertEquals(JvmReminderFire.Outcome.NoNotifier, outcome)
        assertTrue(notifier.posts.isEmpty())
        assertTrue(
            reminderRepo.reminders.value.containsKey(reminder.id.value),
            "the row must survive so the reminder can fire once the host can display again",
        )
    }

    /** A row that is gone, or belongs to another profile, is not an error to report loudly. */
    @Test
    fun `a missing reminder reports NotFound without posting anything`() = runTest {
        val notifier = FakeNotifier()

        val outcome = JvmReminderFire.fire(
            FakeReminderRepository(),
            FakeTaskRepository(),
            notifier,
            ReminderId("never-existed"),
            TestUsers.DEFAULT,
        )

        assertEquals(JvmReminderFire.Outcome.NotFound, outcome)
        assertTrue(notifier.posts.isEmpty())
    }
}
