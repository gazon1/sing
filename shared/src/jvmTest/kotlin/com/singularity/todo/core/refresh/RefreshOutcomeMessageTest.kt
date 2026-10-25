package com.singularity.todo.core.refresh

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.sync.PullSummary
import com.singularity.todo.core.sync.PushSummary
import com.singularity.todo.core.sync.SyncOutcome
import com.singularity.todo.core.ui.components.Notification
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * One test per row of the message table in
 * `specs/015-pull-refresh-sync-archive/contracts/refresh-outcome.md`.
 *
 * The wording is asserted exactly, because the table *is* the contract: the
 * count, the outcome of the synchronisation, and the reason when there is one.
 * A change to any of those is a change to the contract, not to a test.
 */
@Tag("fast")
class RefreshOutcomeMessageTest {

    private fun message(outcome: RefreshOutcome): String {
        val notification = outcome.toNotification()
        assertIs<Notification.Toast>(notification, "the refresh report is a notice, not a dialog")
        return notification.message
    }

    @Test
    fun `tasks were moved and the synchronisation succeeded`() {
        val outcome = RefreshOutcome(
            archived = Result.success(3),
            sync = SyncOutcome.Completed(
                push = Result.success(PushSummary(0, 0, 0)),
                pull = Result.success(PullSummary(0, 0)),
            ),
        )

        assertEquals("Synced. 3 tasks moved to the archive.", message(outcome))
    }

    @Test
    fun `there was nothing to move and the synchronisation succeeded`() {
        val outcome = RefreshOutcome(
            archived = Result.success(0),
            sync = SyncOutcome.Completed(
                push = Result.success(PushSummary(0, 0, 0)),
                pull = Result.success(PullSummary(0, 0)),
            ),
        )

        assertEquals("Synced. Nothing to move to the archive.", message(outcome))
    }

    @Test
    fun `tasks were moved and the synchronisation failed`() {
        val outcome = RefreshOutcome(
            archived = Result.success(3),
            sync = SyncOutcome.CouldNotStart(AppError.Network("no route to host")),
        )

        assertEquals(
            "3 tasks moved to the archive on this device. Sync failed: no route to host",
            message(outcome),
        )
    }

    @Test
    fun `there was nothing to move and the synchronisation failed`() {
        val outcome = RefreshOutcome(
            archived = Result.success(0),
            sync = SyncOutcome.CouldNotStart(AppError.Network("no route to host")),
        )

        assertEquals(
            "Nothing to move to the archive on this device. Sync failed: no route to host",
            message(outcome),
        )
    }

    @Test
    fun `tasks were moved and there was no synchronisation to run`() {
        val outcome = RefreshOutcome(
            archived = Result.success(3),
            sync = SyncOutcome.NothingToDo,
        )

        assertEquals(
            "3 tasks moved to the archive. Sync is unavailable without signing in.",
            message(outcome),
        )
    }

    @Test
    fun `there was nothing to move and there was no synchronisation to run`() {
        val outcome = RefreshOutcome(
            archived = Result.success(0),
            sync = SyncOutcome.NothingToDo,
        )

        assertEquals(
            "Nothing to move to the archive. Sync is unavailable without signing in.",
            message(outcome),
        )
    }

    @Test
    fun `the archive step failed`() {
        val outcome = RefreshOutcome(
            archived = Result.failure(AppError.Persistence("the database is locked")),
            sync = SyncOutcome.Completed(
                push = Result.success(PushSummary(0, 0, 0)),
                pull = Result.success(PullSummary(0, 0)),
            ),
        )

        assertEquals(
            "Could not move completed tasks to the archive: the database is locked",
            message(outcome),
        )
    }

    @Test
    fun `both steps failed`() {
        val outcome = RefreshOutcome(
            archived = Result.failure(AppError.Persistence("the database is locked")),
            sync = SyncOutcome.CouldNotStart(AppError.Network("no route to host")),
        )

        assertEquals(
            "Could not move completed tasks to the archive: the database is locked. " +
                "Sync failed: no route to host",
            message(outcome),
        )
    }

    @Test
    fun `a cycle that ran and lost a phase is a failure`() {
        val outcome = RefreshOutcome(
            archived = Result.success(1),
            sync = SyncOutcome.Completed(
                push = Result.success(PushSummary(0, 0, 0)),
                pull = Result.failure(AppError.Network("the download was cut")),
            ),
        )

        assertEquals(
            "1 task moved to the archive on this device. Sync failed: the download was cut",
            message(outcome),
        )
    }
}
