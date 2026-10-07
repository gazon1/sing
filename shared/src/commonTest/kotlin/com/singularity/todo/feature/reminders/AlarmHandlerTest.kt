package com.singularity.todo.feature.reminders

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.pomodoro.PomodoroPhase
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotifier
import com.singularity.todo.test.fakes.FakeReminderRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What an alarm arrival actually does.
 *
 * ## Why this test file exists at all
 *
 * These decisions used to live inside `AlarmReceiver`, a `BroadcastReceiver`. Testing that
 * would mean Robolectric, and `androidHostTest` declares no tests — the JUnit 4 / Vintage
 * stack it needs is not wired, and the ADR on that records a live trap: Vintage does not
 * map Jupiter's `@Tag`, so such tests are silently skipped. A test that can vanish is worse
 * than no test.
 *
 * So the decisions moved into [AlarmHandler], which takes plain parameters and is tested
 * here in `commonTest`. What is left untested is the `Intent` parsing and `goAsync()`
 * bookkeeping, which are the parts only a receiver can supply and the parts with no
 * decisions in them.
 *
 * ## What was genuinely uncovered
 *
 * The pomodoro branch had **no** test at all: three phases mapping to three titles, one
 * body that flips on a single phase, and a tag that has to include the timestamp or two
 * phase ends replace one another.
 */
@Tag("fast")
class AlarmHandlerTest {

    private val clock = FakeClock()
    private val notifier = FakeNotifier()
    private val reminderRepo = FakeReminderRepository()

    /** Records what was armed; the catch-up is the only thing that schedules. */
    private class RecordingScheduler : ReminderScheduler {
        val scheduled = mutableListOf<ReminderId>()
        override val isSupported: Boolean = true
        override suspend fun schedule(reminder: Reminder) {
            scheduled += reminder.id
        }
        override suspend fun cancel(id: ReminderId, userId: UserId) = Unit
        override suspend fun cancelByTask(taskId: TaskId, userId: UserId) = Unit
    }

    private fun handler(scheduler: RecordingScheduler = RecordingScheduler()) =
        AlarmHandler(
            reminderRepo = reminderRepo,
            reminderScheduler = scheduler,
            delivery = ReminderDelivery(reminderRepo, FakeTaskRepository(), notifier),
            notifier = notifier,
            clock = clock,
        )

    private fun reminder(
        index: Int,
        fireAt: Long,
        recurring: String? = null,
    ) = Reminder(
        id = ReminderId("r$index"),
        taskId = TaskId("t$index"),
        userId = TestUsers.DEFAULT,
        type = ReminderType.Gentle,
        offsetMinutes = 15,
        fireAt = fireAt,
        recurringPattern = recurring,
    )

    // ─── Pomodoro ──────────────────────────────────────────────────────────────

    /**
     * The three phases produce three distinguishable titles.
     *
     * Previously untested, and untestable without pulling Robolectric in for four `when`
     * branches. "Work session ended" and "Short break ended" are the only thing telling a
     * user which one just finished.
     */
    @Test
    fun `each pomodoro phase announces itself distinctly`() = runTest {
        val handler = handler()

        handler.pomodoroPhaseEnded(PomodoroPhase.Work)
        handler.pomodoroPhaseEnded(PomodoroPhase.ShortBreak)
        handler.pomodoroPhaseEnded(PomodoroPhase.LongBreak)

        assertEquals(
            listOf("Work session ended", "Short break ended", "Long break ended"),
            notifier.posts.map { it.title },
        )
    }

    /**
     * The body says what to do next, and only a work session suggests a break.
     *
     * This is the whole point of the notification: the other two phases say "Back to
     * work!", which would be nonsense advice after a break.
     */
    @Test
    fun `only a work session suggests a break`() = runTest {
        val handler = handler()

        handler.pomodoroPhaseEnded(PomodoroPhase.Work)
        handler.pomodoroPhaseEnded(PomodoroPhase.ShortBreak)

        assertEquals("Time for a break ☕", notifier.posts[0].body)
        assertEquals("Back to work!", notifier.posts[1].body)
    }

    /**
     * Successive phase ends must not replace one another.
     *
     * `Notifier.post`'s tag is a *replace* key. A tag without the timestamp would mean
     * the second phase end silently overwrites the first, and the user sees one
     * notification instead of two.
     */
    @Test
    fun `the pomodoro tag distinguishes successive phase ends`() = runTest {
        val handler = handler()

        handler.pomodoroPhaseEnded(PomodoroPhase.Work)
        // Advance the clock: the tag carries the millisecond precisely so that two phase
        // ends in the same session do not share a replace key. Without moving it, both
        // calls land on 0 and the assertion below would be testing the clock, not the tag.
        clock.advance(1.seconds)
        handler.pomodoroPhaseEnded(PomodoroPhase.Work)

        val tags = notifier.posts.map { it.tag }
        assertTrue(
            tags.all { it.startsWith("pomodoro:Work:") },
            "the tag must carry the phase, got $tags",
        )
        assertEquals(
            tags.size,
            tags.distinct().size,
            "two phase ends one second apart must not share a replace key: $tags",
        )
    }

    // ─── Catch-up ──────────────────────────────────────────────────────────────

    /**
     * The load-bearing one: fire what was missed, **then** re-arm.
     *
     * Reversed, a past-due one-shot would be deleted by the fire and re-armed in the same
     * pass, and fire again on the next boot. Retiring a one-shot is its entire job.
     */
    @Test
    fun `catch-up fires the missed ones and re-arms only what is still ahead`() = runTest {
        val now = clock.now().toEpochMilliseconds()
        val missed = reminder(0, fireAt = now - 1_000)
        val upcoming = reminder(1, fireAt = now + 3_600_000)
        reminderRepo.seed(missed, upcoming)
        val scheduler = RecordingScheduler()

        handler(scheduler).catchUp()

        assertEquals(
            listOf(upcoming.id.value),
            scheduler.scheduled.map { it.value },
            "a reminder that already fired must not be re-armed, or it fires forever",
        )
        assertTrue(
            reminderRepo.reminders.value.containsKey(missed.id.value) == false,
            "the past-due one-shot was retired by the fire",
        )
    }

    /**
     * A recurring reminder survives its catch-up fire and is re-armed.
     *
     * The mirror of the one-shot case, and the reason the delete decision exists at all.
     */
    @Test
    fun `a recurring reminder that is past due is re-armed, not retired`() = runTest {
        val now = clock.now().toEpochMilliseconds()
        val series = reminder(0, fireAt = now - 1_000, recurring = "FREQ=DAILY")
        reminderRepo.seed(series)
        val scheduler = RecordingScheduler()

        handler(scheduler).catchUp()

        assertTrue(
            reminderRepo.reminders.value.containsKey(series.id.value),
            "the caller re-arms a series; deleting it here would end it",
        )
        assertEquals(1, notifier.posts.size, "and it was still shown")
    }

    /**
     * The catch-up is capped.
     *
     * A device off for a week has no business presenting forty notifications on first
     * unlock. The cap is the difference between "catch up" and "flood".
     */
    @Test
    fun `catch-up fires at most the capped number of reminders`() = runTest {
        val now = clock.now().toEpochMilliseconds()
        reminderRepo.seed(*(1..30).map { reminder(it, fireAt = now - 1_000 - it) }.toTypedArray())

        handler().catchUp()

        assertEquals(
            20,
            notifier.posts.size,
            "the past-due query is capped; 30 missed reminders must not become 30 notifications",
        )
    }

    /** Nothing missed, nothing to do — and importantly, nothing armed from the stale rows. */
    @Test
    fun `catch-up with nothing due is a no-op`() = runTest {
        val now = clock.now().toEpochMilliseconds()
        reminderRepo.seed(reminder(0, fireAt = now + 3_600_000))
        val scheduler = RecordingScheduler()

        handler(scheduler).catchUp()

        assertTrue(notifier.posts.isEmpty())
        assertEquals(1, scheduler.scheduled.size)
    }

    // ─── Single fire ───────────────────────────────────────────────────────────

    @Test
    fun `a fired reminder is delivered through the shared delivery path`() = runTest {
        val reminderRepo = FakeReminderRepository()
        val notifier = FakeNotifier()
        val notifierPort = notifier
        val r = reminder(7, fireAt = clock.now().toEpochMilliseconds() + 1_000)
        reminderRepo.seed(r)
        AlarmHandler(
            reminderRepo = reminderRepo,
            reminderScheduler = RecordingScheduler(),
            delivery = ReminderDelivery(reminderRepo, FakeTaskRepository(), notifierPort),
            notifier = notifierPort,
            clock = clock,
        ).reminderFired(r.id, r.userId)

        assertEquals(1, notifierPort.posts.size)
    }

    /**
     * A reminder belonging to another profile is still armed at boot.
     *
     * This is the gap the cross-profile read closes. Before it, `catchUp` armed from the
     * profile-scoped query, so a reminder for a profile that was not active on this boot
     * simply never fired — the row existed, the UI said it was set, and nothing reminded
     * anyone until its owner switched to that profile and relaunched.
     *
     * Both halves are asserted: the other profile's reminder is armed, and the active
     * profile's is not special-cased into being the only one.
     */
    @Test
    fun `catch-up arms reminders belonging to other profiles`() = runTest {
        val now = clock.now().toEpochMilliseconds()
        val repo = FakeReminderRepository()
        val scheduler = RecordingScheduler()
        val notifier = FakeNotifier()
        val other = Reminder(
            id = ReminderId("other-profile"),
            taskId = TaskId("t9"),
            userId = UserId("someone-else"),
            type = ReminderType.Gentle,
            offsetMinutes = 15,
            fireAt = now + 60_000,
            recurringPattern = null,
        )
        val mine = Reminder(
            id = ReminderId("active-profile"),
            taskId = TaskId("t10"),
            userId = TestUsers.DEFAULT,
            type = ReminderType.Gentle,
            offsetMinutes = 15,
            fireAt = now + 120_000,
            recurringPattern = null,
        )
        repo.seed(mine, other)

        AlarmHandler(
            reminderRepo = repo,
            reminderScheduler = scheduler,
            delivery = ReminderDelivery(repo, FakeTaskRepository(), notifier),
            notifier = notifier,
            clock = clock,
        ).catchUp()

        assertEquals(
            listOf("active-profile", "other-profile"),
            scheduler.scheduled.map { it.value }.sorted(),
            "arming at boot is a device-wide operation and must not stop at the active profile",
        )
    }
}
