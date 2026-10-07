package com.singularity.todo.feature.reminders

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The Desktop reminder contract.
 *
 * ## The defect this pins
 *
 * `JvmReminderScheduler` implemented all three methods as no-ops. That was correct
 * arithmetic — the desktop app has no alarm scheduler — and dishonest behaviour: every
 * caller wrote the reminder row, the UI reported success, and no alarm ever existed.
 * The user saw a reminder that could not arrive, with nothing to indicate otherwise.
 *
 * ## The two halves of the fix, asserted separately
 *
 * [isSupported] is the *declared* capability: callers consult it before persisting.
 * [RemindersUnsupportedException] from [schedule] is the *enforced* one. The first can
 * be forgotten by a callsite; the second cannot be forgotten silently. Both are tested
 * here because a fix that only added the flag would leave the same bug reachable by the
 * next caller that forgets to check it — which is precisely how the original defect
 * survived: `isSupported` did not exist, and every callsite would have had to invent its
 * own guard.
 *
 * ## Why this is a JVM test and not a common test
 *
 * `isSupported` and the throw are facts about the platform, so asserting them in
 * `commonTest` would test a fake. This runs against the class the desktop app actually
 * binds, which is the only place the claim can be wrong in a way that matters.
 *
 * ## What it does not assert
 *
 * That a reminder *fires* on desktop. It does not, and that is the point — the gate makes
 * the absence visible instead of silent. The replacement backend (`systemd --user`
 * timers) will delete the throwing behaviour and flip `isSupported` to `true`.
 */
@Tag("fast")
class JvmReminderSchedulerCapabilityTest {

    private val scheduler = JvmReminderScheduler()

    @Test
    fun `the desktop scheduler declares itself unsupported`() {
        assertFalse(
            scheduler.isSupported,
            "a no-op scheduler must say so, so callers refuse before persisting a reminder",
        )
    }

    /**
     * The declared flag is what callers are meant to read.
     *
     * Asserting it separately from the throw keeps the two fixes distinguishable: a
     * future change that made `schedule` throw *and* left `isSupported = true` would
     * produce a Desktop where every reminder path fails at runtime with no way for a
     * caller to have known in advance.
     */
    @Test
    fun `schedule refuses rather than silently doing nothing`() = runTest {
        // Built inline rather than via a factory function: `ClassSignature` rejects a
        // multiline constructor call in an expression body, while `FunctionExpressionBody`
        // rejects the block-body form that would satisfy it. Inline sidesteps both, and
        // the reminder is used exactly once anyway.
        val reminder = Reminder(
            id = ReminderId.generate(),
            taskId = TaskId("t1"),
            userId = UserId("u1"),
            type = ReminderType.Gentle,
            offsetMinutes = 15,
            fireAt = 1_800_000_000_000L,
            recurringPattern = null,
        )

        val thrown = assertFailsWith<RemindersUnsupportedException> {
            scheduler.schedule(reminder)
        }

        assertEquals(
            "schedule a reminder",
            thrown.operation,
            "the exception must name the operation, so a caller can tell which seam was inert",
        )
    }

    /**
     * Cancelling is deliberately *not* symmetric with scheduling.
     *
     * Nothing was ever armed on this platform, so "cancelled" is already the true state
     * and there is nothing to report. Throwing here would break cleanup paths — deleting
     * a task, dropping a due date, importing from Google — for no benefit, and would
     * leave a user unable to remove rows written by an older build.
     *
     * This asymmetry is the part most likely to be "tidied up" later by someone reading
     * the class and assuming the three methods should match, so it is pinned explicitly.
     */
    @Test
    fun `cancel and cancelByTask stay silent because nothing was ever armed`() = runTest {
        val reminder = Reminder(
            id = ReminderId.generate(),
            taskId = TaskId("t1"),
            userId = UserId("u1"),
            type = ReminderType.Gentle,
            offsetMinutes = 15,
            fireAt = 1_800_000_000_000L,
            recurringPattern = null,
        )

        scheduler.cancel(reminder.id, reminder.userId)
        scheduler.cancelByTask(reminder.taskId, reminder.userId)

        assertFalse(
            scheduler.isSupported,
            "cleanup must not change what the platform supports",
        )
    }

    // A project reminder has no scheduler on *any* platform, so there is deliberately
    // no test here for it: the project UI is gated by the separate
    // `PROJECT_REMINDERS_SUPPORTED` constant rather than by this scheduler. The two
    // facts are easy to conflate, and the KDoc this file replaces conflated them.
}
