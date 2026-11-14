package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.CreateTaskInput
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeUnitOfWork
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Every create door carries the attributes the editor can set.
 *
 * ## Why this file exists
 *
 * `TaskDomain.createInput` re-validates and **rebuilds** a `CreateTaskInput`, so a
 * field a caller does not forward is dropped and `buildTask` persists a task
 * without it. That is not hypothetical: `parentTaskId` was omitted from one door
 * and silently turned "add subtask" into "add a top-level task", and the estimate
 * and the pin were omitted from these doors after the editor began offering them.
 *
 * The editor's own test walks one door. These walk the other two, so the defect
 * cannot come back through a path the editor's test does not see.
 */
@Tag("fast")
class CreateTaskAttributeCarryOverTest {
    private val now: Instant = Instant.parse("2026-11-14T09:00:00Z")
    private val repo = FakeTaskRepository()
    private val currentUser: ProfileAwareCurrentUser =
        FakeProfileAwareCurrentUser(
            FakeAuthRepository(initialSession = Session.Anonymous(UserId("carry-over-user"))),
        )

    private fun created(): Task =
        repo.tasks.value.values
            .first()

    // ── CreateTaskUseCase ────────────────────────────────────────────────────

    @Test
    fun `CreateTaskUseCase keeps the estimate and the pin`() =
        runTest {
            val useCase = CreateTaskUseCase(repo, FakeClock(now), currentUser)

            val result =
                useCase(
                    CreateTaskInput(
                        title = "Estimated and pinned",
                        estimateMinutes = 45,
                        isPinned = true,
                    ),
                )

            assertTrue(result.isSuccess, "the create failed: ${result.exceptionOrNull()}")
            assertEquals(45, created().estimateMinutes, "the estimate was dropped on the way to the row")
            assertTrue(created().isPinned, "the pin was dropped on the way to the row")
        }

    @Test
    fun `CreateTaskUseCase leaves both at their defaults when the caller sets neither`() =
        runTest {
            val useCase = CreateTaskUseCase(repo, FakeClock(now), currentUser)

            useCase(CreateTaskInput(title = "Plain task")).getOrThrow()

            assertEquals(null, created().estimateMinutes)
            assertEquals(false, created().isPinned)
        }

    // ── CreateRecurringTaskUseCase ───────────────────────────────────────────

    @Test
    fun `CreateRecurringTaskUseCase keeps the estimate on the series it creates`() =
        runTest {
            val useCase =
                CreateRecurringTaskUseCase(
                    repo = repo,
                    clock = FakeClock(now),
                    currentUser = currentUser,
                    unitOfWork = FakeUnitOfWork(),
                    materialize = MaterializeRecurringSeriesUseCase(repo, FakeUnitOfWork(), FakeClock(now)),
                )

            useCase(
                CreateTaskInput(
                    title = "Weekly review",
                    dueDate = LocalDate(2026, 11, 16),
                    estimateMinutes = 30,
                    isPinned = true,
                    recurrence =
                        RecurrenceSpec.Interval(
                            base = RecurrenceBase.FROM_DUE,
                            amount = 1,
                            unit = DateTimeUnit.WEEK,
                        ),
                ),
            ).getOrThrow()

            val rows = repo.tasks.value.values
            assertTrue(rows.isNotEmpty(), "the recurring create wrote no rows")

            val anchor = rows.first { it.seriesId != null }
            assertEquals(30, anchor.estimateMinutes, "the estimate was dropped by the recurring door")
            assertTrue(anchor.isPinned, "the pin was dropped by the recurring door")
        }

    /**
     * The estimate is a shared field of a series; the pin is not.
     *
     * `TaskSeriesPolicy` states this: the estimate describes the work, and the pin
     * is one of the per-occurrence facts — pinning Monday must not pin Wednesday.
     * The materialised occurrences are the check that the contract and the planner
     * still agree.
     */
    @Test
    fun `materialised occurrences inherit the estimate and keep their own pin`() =
        runTest {
            val useCase =
                CreateRecurringTaskUseCase(
                    repo = repo,
                    clock = FakeClock(now),
                    currentUser = currentUser,
                    unitOfWork = FakeUnitOfWork(),
                    materialize = MaterializeRecurringSeriesUseCase(repo, FakeUnitOfWork(), FakeClock(now)),
                )

            useCase(
                CreateTaskInput(
                    title = "Daily standup",
                    dueDate = LocalDate(2026, 11, 16),
                    estimateMinutes = 15,
                    isPinned = true,
                    recurrence =
                        RecurrenceSpec.Interval(
                            base = RecurrenceBase.FROM_DUE,
                            amount = 1,
                            unit = DateTimeUnit.DAY,
                        ),
                ),
            ).getOrThrow()

            val members =
                repo.tasks.value.values
                    .filter { it.seriesId != null }
            assertTrue(members.size > 1, "one day's rule produced no further occurrence: ${members.size} row(s)")
            members.forEach { member ->
                assertEquals(
                    15,
                    member.estimateMinutes,
                    "an occurrence lost the estimate: ${member.dueDate}",
                )
                assertTrue(
                    !member.isPinned || member.id.value == members.first().id.value,
                    "the pin leaked to a later occurrence: ${member.dueDate}",
                )
            }
        }

    /** An untouched door still produces a series when the rule is set. */
    @Test
    fun `a rule without the new attributes still creates a series`() =
        runTest {
            val useCase =
                CreateRecurringTaskUseCase(
                    repo = repo,
                    clock = FakeClock(now),
                    currentUser = currentUser,
                    unitOfWork = FakeUnitOfWork(),
                    materialize = MaterializeRecurringSeriesUseCase(repo, FakeUnitOfWork(), FakeClock(now)),
                )

            useCase(
                CreateTaskInput(
                    title = "Unestimated standup",
                    dueDate = LocalDate(2026, 11, 16),
                    recurrence =
                        RecurrenceSpec.Interval(
                            base = RecurrenceBase.FROM_DUE,
                            amount = 1,
                            unit = DateTimeUnit.DAY,
                        ),
                ),
            ).getOrThrow()

            val rows = repo.tasks.value.values
            assertTrue(rows.isNotEmpty())
            assertEquals(null, rows.first().estimateMinutes)
            assertTrue(rows.none { it.isPinned })
        }
}
