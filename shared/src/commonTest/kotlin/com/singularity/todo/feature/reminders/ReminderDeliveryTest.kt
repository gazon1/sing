package com.singularity.todo.feature.reminders

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeNotifier
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
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
 * What happens to a reminder once its platform has decided to fire it.
 *
 * ## Why this is in `commonTest`
 *
 * This used to be a JVM-only test over `JvmReminderFire.fire`, which was one of **three**
 * copies of these four steps: the Android `AlarmReceiver` single-fire path, its boot
 * catch-up loop, and the Desktop launcher. A JVM-only test could only ever cover one of
 * them, which is how three copies survived a codebase this disciplined.
 *
 * The logic now lives in [ReminderDelivery], which both platforms call, so the coverage
 * belongs in `commonTest` where it constrains every caller. If Android's receiver ever
 * goes back to assembling its own notification, these tests stop saying anything true
 * about the Android path — which is the point, and is detectable only because the class
 * is shared rather than duplicated.
 */
@Tag("fast")
class ReminderDeliveryTest {

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

    private fun delivery(
        reminderRepo: FakeReminderRepository = FakeReminderRepository(),
        taskRepo: FakeTaskRepository = FakeTaskRepository(),
        notifier: FakeNotifier = FakeNotifier(supported = true),
    ) = ReminderDelivery(reminderRepo, taskRepo, notifier)

    /**
     * The title is read now, not remembered from arm time.
     *
     * The whole reason the Desktop backend runs a launcher that opens the database rather
     * than printing a title baked into a systemd unit. Renaming a task after arming must
     * change what the notification says.
     */
    @Test
    fun `the notification carries the task title as it is at fire time`() = runTest {
        val reminderRepo = FakeReminderRepository()
        val taskRepo = FakeTaskRepository()
        val notifier = FakeNotifier()
        val reminder = reminder()
        reminderRepo.seed(reminder)
        taskRepo.seed(testTask(id = reminder.taskId, title = "Renamed after arming"))

        val outcome = delivery(reminderRepo, taskRepo, notifier).fire(reminder.id, reminder.userId)

        assertEquals(ReminderDelivery.Outcome.Posted, outcome)
        assertEquals(
            "Reminder: Renamed after arming",
            notifier.posts.single().title,
            "a stale title here is the defect the launcher's database read exists to avoid",
        )
    }

    /**
     * A deleted task still produces a notification, with the generic title.
     *
     * `ReminderFireLogic` decides this, and it is deliberate: the user asked to be
     * reminded about something, and "Task Reminder" is more use than silence.
     */
    @Test
    fun `a reminder whose task is gone falls back to the generic title`() = runTest {
        val reminderRepo = FakeReminderRepository()
        val notifier = FakeNotifier()
        val reminder = reminder()
        reminderRepo.seed(reminder)

        delivery(reminderRepo, taskRepo = FakeTaskRepository(), notifier = notifier)
            .fire(reminder.id, reminder.userId)

        assertEquals("Task Reminder", notifier.posts.single().title)
    }

    /** The tag is the same one every other reminder uses, so nothing can double-post. */
    @Test
    fun `the notification is tagged by the firing profile`() = runTest {
        val reminderRepo = FakeReminderRepository()
        val notifier = FakeNotifier()
        val reminder = reminder()
        reminderRepo.seed(reminder)

        delivery(reminderRepo, notifier = notifier).fire(reminder.id, reminder.userId)

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
        val reminder = reminder(recurring = null)
        reminderRepo.seed(reminder)

        delivery(reminderRepo).fire(reminder.id, reminder.userId)

        assertNull(
            reminderRepo.reminders.value[reminder.id.value],
            "a one-shot that is not deleted is re-armed on every launch, forever",
        )
    }

    @Test
    fun `a recurring reminder survives its fire`() = runTest {
        val reminderRepo = FakeReminderRepository()
        val reminder = reminder(recurring = "FREQ=DAILY")
        reminderRepo.seed(reminder)

        delivery(reminderRepo).fire(reminder.id, reminder.userId)

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
     * off than if it had never been created — and the log is the only place that fact will
     * ever appear.
     */
    @Test
    fun `nothing is posted and the row is kept when the host cannot display`() = runTest {
        val reminderRepo = FakeReminderRepository()
        val notifier = FakeNotifier(supported = false)
        val reminder = reminder()
        reminderRepo.seed(reminder)

        val outcome = delivery(reminderRepo, notifier = notifier).fire(reminder.id, reminder.userId)

        assertEquals(ReminderDelivery.Outcome.NoNotifier, outcome)
        assertTrue(notifier.posts.isEmpty())
        assertTrue(
            reminderRepo.reminders.value.containsKey(reminder.id.value),
            "the row must survive so the reminder can fire once the host can display again",
        )
    }

    /** A row that is gone, or belongs to another profile, is not an error worth shouting about. */
    @Test
    fun `a missing reminder reports NotFound without posting anything`() = runTest {
        val notifier = FakeNotifier()

        val outcome = delivery(notifier = notifier).fire(ReminderId("never-existed"), TestUsers.DEFAULT)

        assertEquals(ReminderDelivery.Outcome.NotFound, outcome)
        assertTrue(notifier.posts.isEmpty())
    }

    /**
     * The boot catch-up path, which enumerates rows instead of looking each one up.
     *
     * It exists as a separate entry point because re-reading each row through `get()` would
     * filter it by the active profile — dropping reminders from a batch that was built
     * precisely to be profile-complete. Pinned here because that is the one behavioural
     * difference between the two entry points, and it is the kind of difference that gets
     * "tidied up" into a bug.
     */
    @Test
    fun `fireKnown posts and retires a row the caller already holds`() = runTest {
        val reminderRepo = FakeReminderRepository()
        val notifier = FakeNotifier()
        val reminder = reminder()
        reminderRepo.seed(reminder)

        val outcome = delivery(reminderRepo, notifier = notifier).fireKnown(reminder)

        assertEquals(ReminderDelivery.Outcome.Posted, outcome)
        assertEquals(
            "reminder:${reminder.userId.value}:${reminder.id.value}",
            notifier.posts.single().tag,
            "an enumerated row is charged to its own profile",
        )
        assertNull(reminderRepo.reminders.value[reminder.id.value])
    }

    @Test
    fun `fireKnown keeps a recurring row`() = runTest {
        val reminderRepo = FakeReminderRepository()
        val reminder = reminder(recurring = "FREQ=DAILY")
        reminderRepo.seed(reminder)

        delivery(reminderRepo).fireKnown(reminder)

        assertTrue(reminderRepo.reminders.value.containsKey(reminder.id.value))
    }

    /**
     * The ordering invariant, made observable.
     *
     * `ReminderRepository.get` is scoped to the **active** profile. Both platforms'
     * entry points therefore resolve the profile before firing — Desktop's
     * `fireReminder` runs `ProfileBootstrapper` before touching the repository, Android's
     * receiver inherits it from process start. That ordering is a comment in two files
     * that no test can reach, which is a comment that will eventually be moved.
     *
     * What *is* testable is the consequence, and the consequence is severe: a fire that
     * happens too early finds nothing and reports `NotFound` for a reminder that exists —
     * a silent failure carrying a message that actively points at the wrong cause.
     *
     * So this pins the contrast. Same reminder, same delivery, only the active profile
     * differs. If someone makes `get` profile-independent to "fix" a NotFound they cannot
     * reproduce, this test is what tells them they broke profile isolation instead.
     */
    @Test
    fun `a reminder owned by another profile is not found`() = runTest {
        val notifier = FakeNotifier()
        val reminder = reminder()
        // Seeded regardless of who is active: `seed` writes the store directly.
        val reminderRepo = FakeReminderRepository(
            currentUser = FakeProfileAwareCurrentUser(UserId("someone-else")),
        ).apply { seed(reminder) }

        val outcome = ReminderDelivery(reminderRepo, FakeTaskRepository(), notifier)
            .fire(reminder.id, reminder.userId)

        assertEquals(
            ReminderDelivery.Outcome.NotFound,
            outcome,
            "get() is scoped to the active profile, so this is what a caller sees when it " +
                "fires before resolving one",
        )
        assertTrue(notifier.posts.isEmpty(), "another profile's reminder must not be posted")
    }

    /**
     * A failed delete must not un-post the notification.
     *
     * The notification was shown, which is what the user was promised. Leaving the row
     * behind means the reminder fires again, which is recoverable; withholding the
     * notification is not. This is the one place where the two platforms previously had
     * no shared code and could have disagreed about whether a failed delete is a failure.
     */
    @Test
    fun `a failed delete still reports Posted`() = runTest {
        val reminderRepo = FakeReminderRepository().apply {
            deleteWithUserIdOverride = Result.failure(IllegalStateException("db gone"))
        }
        val notifier = FakeNotifier()
        val reminder = reminder()
        reminderRepo.seed(reminder)

        val outcome = delivery(reminderRepo, notifier = notifier).fire(reminder.id, reminder.userId)

        assertEquals(ReminderDelivery.Outcome.Posted, outcome)
        assertEquals(1, notifier.posts.size)
    }
}
