package com.singularity.todo.feature.projects.presentation.viewmodel

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.reminders.ProjectReminder
import com.singularity.todo.feature.reminders.ProjectReminderId
import com.singularity.todo.test.fakes.FakeProjectRemindersRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Unit tests for [ProjectReminderCoordinator].
 *
 * The rule under test is `fireAt = startOfDueDate − offset`, and the trap is the
 * round trip: the coordinator *writes* an absolute instant and *reads back* an
 * offset for the picker. If those two drift apart the user is shown a different
 * value from the one they chose, which no amount of state-level assertion makes
 * obvious — so it is asserted here, on the coordinator, directly.
 *
 * Every assertion that touches an instant computes the expected value through
 * `TimeZone.currentSystemDefault()` the same way the production code does, so
 * these tests are timezone-independent rather than pinned to the runner's zone.
 */
@Tag("fast")
class ProjectReminderCoordinatorTest {

    private val projectId = ProjectId("p1")
    private val owner = UserId("test-user")
    private val due = LocalDate(2026, 10, 15)
    private val at = Instant.parse("2026-01-15T12:00:00Z")

    private val reminders = FakeProjectRemindersRepository()

    /** Mutable stand-in for the ViewModel's `_projectFlow`, which the coordinator reads as a snapshot. */
    private val currentProject = MutableStateFlow(project(dueDate = due))

    private val defaultOffset = MutableStateFlow<ReminderOffset>(ReminderOffset.ONE_HOUR)

    private fun coordinator(
        project: () -> Project? = { currentProject.value },
        default: Flow<ReminderOffset> = defaultOffset,
    ) = ProjectReminderCoordinator(
        projectId = projectId,
        project = project,
        reminders = reminders,
        defaultOffset = default,
    )

    private fun project(dueDate: LocalDate?) = Project(
        id = projectId,
        name = "Test Project",
        color = 0,
        createdAt = at,
        updatedAt = at,
        dueDate = dueDate,
        userId = owner,
    )

    private fun dueStartOfDayMillis() =
        due.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    // ─── Reading the offset back ────────────────────────────────────────────────

    @Test
    fun `with no reminder the project has no offset of its own`() = runTest {
        val offsets = coordinator().offsets.first()

        assertEquals(null, offsets.current)
        assertEquals(ReminderOffset.ONE_HOUR, offsets.default)
    }

    @Test
    fun `a stored instant is read back as an offset from the due date`() = runTest {
        val wanted = ReminderOffset.ONE_DAY
        reminders.seed(
            ProjectReminder(
                id = ProjectReminderId("r1"),
                projectId = projectId,
                userId = owner,
                fireAt = dueStartOfDayMillis() - wanted.minutes * 60_000L,
            ),
        )

        assertEquals(wanted.minutes, coordinator().offsets.first().current)
    }

    @Test
    fun `a project with no due date reports no offset even when a row exists`() = runTest {
        currentProject.value = project(dueDate = null)
        reminders.seed(
            ProjectReminder(
                id = ProjectReminderId("r1"),
                projectId = projectId,
                userId = owner,
                fireAt = dueStartOfDayMillis(),
            ),
        )

        assertEquals(
            null,
            coordinator().offsets.first().current,
            "there is nothing to measure the offset from",
        )
    }

    // ─── The round trip ─────────────────────────────────────────────────────────

    @Test
    fun `set stores fireAt exactly one offset before the start of the due date`() = runTest {
        val outcome = coordinator().set(ReminderOffset.ONE_DAY.minutes)

        assertEquals(SetReminderOutcome.Applied, outcome)
        val stored = reminders.all().single()
        assertEquals(
            ReminderOffset.ONE_DAY.minutes * 60_000L,
            dueStartOfDayMillis() - stored.fireAt,
            "the alarm must be anchored to the due date, not to 'now'",
        )
    }

    @Test
    fun `what set writes is what offsets reads back`() = runTest {
        val chosen = ReminderOffset.ONE_HOUR.minutes
        val subject = coordinator()

        subject.set(chosen)

        assertEquals(chosen, subject.offsets.first().current)
    }

    /**
     * Re-picking must update the existing row.
     *
     * A generated id per change would leave one alarm row per pick, and every
     * later `watchByProject` would then resolve against whichever row sorted
     * first — a silent drift between what the picker shows and what is stored.
     */
    @Test
    fun `setting twice updates in place instead of stacking rows`() = runTest {
        val subject = coordinator()

        subject.set(ReminderOffset.ONE_HOUR.minutes)
        val firstId = reminders.all().single().id

        subject.set(ReminderOffset.ONE_DAY.minutes)

        assertEquals(1, reminders.all().size)
        assertEquals(firstId, reminders.all().single().id)
    }

    // ─── Removal ────────────────────────────────────────────────────────────────

    @Test
    fun `a null offset removes the reminder`() = runTest {
        val subject = coordinator()
        subject.set(ReminderOffset.ONE_HOUR.minutes)

        val outcome = subject.set(null)

        assertEquals(SetReminderOutcome.Removed, outcome)
        assertEquals(emptyList(), reminders.all())
    }

    @Test
    fun `a null offset on a project with no reminder is not an error`() = runTest {
        assertEquals(SetReminderOutcome.Removed, coordinator().set(null))
    }

    // ─── Refusals ───────────────────────────────────────────────────────────────

    @Test
    fun `an offset with no due date is rejected, not silently dropped`() = runTest {
        currentProject.value = project(dueDate = null)

        val outcome = coordinator().set(ReminderOffset.ONE_HOUR.minutes)

        assertEquals(SetReminderOutcome.NoDueDate, outcome)
        assertEquals(emptyList(), reminders.all(), "a rejected request must not write a row")
    }

    @Test
    fun `a refused write is reported rather than thrown`() = runTest {
        val boom = IllegalStateException("disk full")
        reminders.upsertOverride = Result.failure(boom)

        val outcome = coordinator().set(ReminderOffset.ONE_HOUR.minutes)

        assertEquals(SetReminderOutcome.Failed(boom), outcome)
    }

    @Test
    fun `a refused delete is reported rather than thrown`() = runTest {
        val subject = coordinator()
        subject.set(ReminderOffset.ONE_HOUR.minutes)
        val boom = IllegalStateException("locked")
        reminders.deleteByProjectOverride = Result.failure(boom)

        assertIs<SetReminderOutcome.Failed>(subject.set(null))
    }

    // ─── The default never writes ───────────────────────────────────────────────

    /**
     * The default preselects; it never creates.
     *
     * A reminder the user did not ask for is a notification they cannot explain,
     * which is why the default is threaded through as a value rather than applied
     * on the way in.
     */
    @Test
    fun `reading the default does not create a reminder`() = runTest {
        defaultOffset.value = ReminderOffset.ONE_DAY

        val offsets = coordinator().offsets.first()

        assertEquals(ReminderOffset.ONE_DAY, offsets.default)
        assertEquals(emptyList(), reminders.all())
    }
}
