package com.singularity.todo.feature.tasks.domain

import com.singularity.todo.core.error.Either
import com.singularity.todo.core.text.FieldOwnedText
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskFromDraftUseCase
import com.singularity.todo.feature.tasks.domain.usecase.MaterializeRecurringSeriesUseCase
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
import com.singularity.todo.feature.tasks.presentation.state.TaskDraft
import com.singularity.todo.test.fakes.FakeAttachmentRepository
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeUnitOfWork
import com.singularity.todo.test.fakes.TEST_TZ
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The create screen's door to a recurring series.
 *
 * The create path used to drop the rule: the series planner cleared it on every row
 * it wrote, including the single row of a one-date draft — so a task created with
 * "repeat every week" came out with no rule at all, silently. This is the test that
 * the rule survives the create, and that a rule together with picked dates loses.
 */
@Tag("fast")
class CreateTaskFromDraftUseCaseTest {

    private val today = LocalDate(2026, 6, 15)
    private val clock = FakeClock(Instant.parse("2026-06-15T10:00:00Z"))
    private val repo = FakeTaskRepository()
    private val unitOfWork = FakeUnitOfWork()

    private val weekly = RecurrenceSpec.Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.WEEK)

    private fun materialize() = MaterializeRecurringSeriesUseCase(repo, unitOfWork, clock)

    private fun useCase() = CreateTaskFromDraftUseCase(
        repo = repo,
        clock = clock,
        timeZone = TEST_TZ,
        currentUser = FakeProfileAwareCurrentUser(),
        checklistRepository = FakeChecklistRepository(),
        attachmentRepository = FakeAttachmentRepository(),
        unitOfWork = unitOfWork,
        materialize = materialize(),
    )

    private fun draft(
        date: LocalDate = today,
        rule: RecurrenceSpec? = null,
        extraDates: List<LocalDate> = emptyList(),
    ) = TaskDraft(
        title = FieldOwnedText.empty().seeded("Buy bread"),
        dueDate = DueDateOption.Custom(date, date.toString()),
        extraDueDates = extraDates,
        recurrence = rule,
    )

    @Test
    fun `a created repeating task carries the rule`() = runTest {
        val result = useCase()(draft(rule = weekly))

        val id = (result as Either.Right).value
        val row = repo.get(id)
        assertEquals(weekly, row?.recurrence, "the created row carries the rule — the create path no longer drops it")
        assertNotNull(row?.seriesId, "and it belongs to a series")
    }

    @Test
    fun `a created repeating task materialises the future occurrences`() = runTest {
        val result = useCase()(draft(rule = weekly))

        val id = (result as Either.Right).value
        val seriesId = repo.get(id)?.seriesId
        assertNotNull(seriesId, "the created row belongs to a series")
        val future = repo.tasks.value.values.filter {
            it.seriesId == seriesId && it.completedAt == null && it.id != id
        }
        assertTrue(future.isNotEmpty(), "the series holds the rule's future dates as real rows")
        assertTrue(future.all { it.recurrence == weekly }, "every occurrence carries the rule")
    }

    @Test
    fun `a draft with a rule and a second date drops the rule`() = runTest {
        // A rule together with picked dates: the rule loses (FR-048), which is what
        // the create screen already tells the user.
        val result = useCase()(draft(rule = weekly, extraDates = listOf(today.plus(1, DateTimeUnit.DAY))))

        val id = (result as Either.Right).value
        val row = repo.get(id)
        assertNull(row?.recurrence, "the rule loses against picked dates")
        assertNotNull(row?.seriesId, "a multi-date draft is still a series")
        assertTrue(
            repo.tasks.value.values.all { it.recurrence == null },
            "no row of a picked-dates series carries a rule",
        )
    }

    @Test
    fun `a draft without a rule is a single ordinary task`() = runTest {
        val result = useCase()(draft(rule = null))

        val id = (result as Either.Right).value
        val row = repo.get(id)
        assertNull(row?.recurrence, "no rule, no repeating")
        assertNull(row?.seriesId, "a single date is an ordinary task, not a series")
    }
}
