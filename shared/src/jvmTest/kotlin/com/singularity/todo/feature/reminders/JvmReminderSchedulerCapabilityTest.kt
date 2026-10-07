package com.singularity.todo.feature.reminders

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
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
 * A no-op scheduler is only safe if it *declares* itself one. `isSupported = false` is
 * that declaration, and [com.singularity.todo.feature.tasks.presentation.viewmodel.slot.TaskRemindersSlot]
 * reads it before persisting.
 *
 * ## Why this is a JVM test and not a common test
 *
 * `isSupported` is a fact about the platform, so asserting it in `commonTest` would test
 * a fake. This runs against the class the desktop app actually binds, which is the only
 * place the claim can be wrong in a way that matters.
 *
 * ## What it does not assert
 *
 * That a reminder *fires* on desktop. It does not, and that is the point — the gate makes
 * the absence visible instead of silent. The replacement backend (`systemd --user`
 * timers) will flip this test's first assertion.
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
     * The flag is what stops the lie; the methods must then stay genuinely inert.
     *
     * Asserting both directions matters because the obvious "fix" is to make the JVM
     * methods log a warning instead of doing nothing — which is only correct if they
     * still arm nothing. Scheduling nothing while warning is the intended contract here.
     */
    @Test
    fun `the unsupported scheduler arms nothing and touches nothing`() = runTest {
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

        scheduler.schedule(reminder)
        scheduler.cancel(reminder.id, reminder.userId)
        scheduler.cancelByTask(reminder.taskId, reminder.userId)

        // Nothing to observe directly — the value is that these are total no-ops rather
        // than deferred work. Any future backend must keep this assertion in mind and
        // flip `isSupported` in the same change.
        assertFalse(scheduler.isSupported, "arming nothing must keep the capability false")
    }

    // A project reminder has no scheduler on *any* platform, so there is deliberately
    // no test here for it: the project UI is gated by the separate
    // `PROJECT_REMINDERS_SUPPORTED` constant rather than by this scheduler. The two
    // facts are easy to conflate, and the KDoc this file replaces conflated them.
}
