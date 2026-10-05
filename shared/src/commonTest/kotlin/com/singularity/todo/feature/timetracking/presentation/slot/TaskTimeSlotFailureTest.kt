package com.singularity.todo.feature.timetracking.presentation.slot

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.TimeEntryId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.TaskTimeSlotIntent
import com.singularity.todo.feature.timetracking.domain.TimeEntry
import com.singularity.todo.feature.timetracking.domain.TimeEntryKind
import com.singularity.todo.feature.timetracking.domain.TimeEntrySource
import com.singularity.todo.feature.timetracking.domain.model.TaskTimeSlotState
import com.singularity.todo.feature.timetracking.domain.port.TimeTrackingRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTimeTrackingRepository
import com.singularity.todo.test.helpers.MutableClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A failed time-tracking write has to become a state, not a silence.
 *
 * All three writes in [TaskTimeSlot] used to end in
 * `onFailure { /* UI updates from the flow */ }` — one wrong sentence, copied
 * three times in one file. It is wrong whenever the *write* fails, because then
 * there is nothing for the flow to update from: the chip stays on Start, nothing
 * is logged, nothing is emitted, and a click that did nothing is
 * indistinguishable from a control that was never wired.
 *
 * That is not a hypothetical. The desktop harness session is `Anonymous`,
 * `startEntry` cannot succeed there, and the first `TASK-TIME-01` desktop
 * carrier failed with a `ComposeTimeoutException` on *"no node with
 * `time_tracking_start` remains"* — a timeout that reads as a UI problem and is
 * actually a swallowed data failure. The carrier had to be thrown away, and the
 * scenario's cell stayed a hole.
 *
 * The class had no test at all before this one, which is the other half of the
 * finding: a defect in a class nobody exercises cannot be caught by anything,
 * including review.
 */
@Tag("fast")
class TaskTimeSlotFailureTest {

    private fun slot(
        repo: TimeTrackingRepository,
        scope: AutoCloseableCoroutineScope,
        task: Task? = null,
    ): TaskTimeSlot {
        val flow = MutableStateFlow(task)
        return TaskTimeSlot(
            taskId = task?.id ?: TaskId("t1"),
            timeTrackingRepo = repo,
            currentUser = FakeProfileAwareCurrentUser(),
            scope = scope,
            taskFlow = flow,
        )
    }

    private fun aTask(): Task = Task(
        id = TaskId("t1"),
        title = "Track me",
        // Only the three without defaults. Read from the declaration rather than
        // guessed: the first attempt invented `isCompleted`/`isArchived`, which
        // do not exist, and omitted these three, which do.
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
        userId = UserId("u1"),
    )

    @Test
    fun a_refused_start_becomes_an_error_state() = runTest {
        val scope = AutoCloseableCoroutineScope(StandardTestDispatcher(testScheduler))
        val refusing = RefusingTimeTrackingRepository("no signed-in user")
        val subject = slot(refusing, scope, aTask())

        subject.onIntent(TaskTimeSlotIntent.Start)
        advanceUntilIdle()

        val state = assertIs<TaskTimeSlotState.Error>(subject.state.value)
        assertTrue(
            state.message.contains("no signed-in user"),
            "the refusal must reach the user verbatim, got '${state.message}'",
        )
    }

    @Test
    fun a_refused_stop_becomes_an_error_state() = runTest {
        val scope = AutoCloseableCoroutineScope(StandardTestDispatcher(testScheduler))
        val subject = slot(RefusingTimeTrackingRepository("offline"), scope, aTask())

        subject.onIntent(TaskTimeSlotIntent.Stop)
        advanceUntilIdle()

        assertTrue(
            assertIs<TaskTimeSlotState.Error>(subject.state.value).message.contains("offline"),
        )
    }

    @Test
    fun a_successful_start_is_not_an_error() = runTest {
        // The negative case, because a gate that only knows how to report failure
        // is satisfied by a repository that always fails. This one passes through
        // the real fake, so the assertion is about the happy path not being
        // swallowed in the other direction.
        val scope = AutoCloseableCoroutineScope(StandardTestDispatcher(testScheduler))
        val subject = slot(FakeTimeTrackingRepository(MutableClock()), scope, aTask())

        subject.onIntent(TaskTimeSlotIntent.Start)
        advanceUntilIdle()

        assertTrue(
            subject.state.value !is TaskTimeSlotState.Error,
            "a successful write must not produce an error state",
        )
    }

    private class RefusingTimeTrackingRepository(private val why: String) :
        TimeTrackingRepository by FakeTimeTrackingRepository(MutableClock()) {
        override suspend fun startEntry(
            taskId: TaskId,
            userId: UserId,
            kind: TimeEntryKind,
            source: TimeEntrySource,
        ): Result<TimeEntryId> = Result.failure(IllegalStateException(why))

        override suspend fun stopEntry(userId: UserId): Result<TimeEntry?> =
            Result.failure(IllegalStateException(why))
    }
}
