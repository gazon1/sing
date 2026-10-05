package com.singularity.todo.feature.tasks.domain

import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.tasks.domain.logic.RecurrenceCalculator
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceTermination
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.isExhaustedBy
import com.singularity.todo.feature.tasks.domain.usecase.CompleteRecurringTaskUseCase
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.TEST_TZ
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A recurring series with an end date stops instead of rolling forward forever.
 *
 * The boundary is inclusive — an occurrence landing *on* the end date still runs,
 * because "repeat until Dec 31" means Dec 31 is included. Everything here pins
 * that edge, because a series that stops one occurrence early or late is
 * indistinguishable from correct behaviour in ordinary use.
 */
@Tag("fast")
class CompleteRecurringTaskUseCaseTest {

    private val today = LocalDate(2026, 6, 15)
    private val clock = FakeClock(Instant.parse("2026-06-15T10:00:00Z"))
    private val repo = FakeTaskRepository()

    private val weekly = RecurrenceSpec.Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.WEEK)

    private fun useCase() = CompleteRecurringTaskUseCase(repo, clock, TEST_TZ, RecurrenceCalculator)

    private fun recurringTask(
        dueDate: LocalDate = today,
        spec: RecurrenceSpec = weekly,
    ) = Task(
        id = TaskId("t1"),
        title = "Buy bread",
        dueDate = dueDate,
        recurrence = spec,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        userId = TestUsers.DEFAULT,
    )

    private fun boundedUntil(endDate: LocalDate) =
        weekly.copy(termination = RecurrenceTermination(endDate))

    // ─── The boundary itself ──────────────────────────────────────────────────

    @Test
    fun `an occurrence on the end date still runs`() = runTest {
        // Due today, next weekly occurrence is today+7. Ending on exactly that
        // day must still recur — the end date is inclusive.
        repo.seed(recurringTask(dueDate = today, spec = boundedUntil(today.plus(7, DateTimeUnit.DAY))))

        useCase()(TaskId("t1"))

        val task = repo.get(TaskId("t1"))
        assertNull(task?.archivedAt, "must not stop on the end date itself")
        assertEquals(today.plus(7, DateTimeUnit.DAY), task?.dueDate)
    }

    @Test
    fun `an occurrence one day past the end date stops the series`() = runTest {
        repo.seed(recurringTask(dueDate = today, spec = boundedUntil(today.plus(6, DateTimeUnit.DAY))))

        useCase()(TaskId("t1"))

        val task = repo.get(TaskId("t1"))
        assertNotNull(task?.archivedAt, "one day past the end date the series must stop")
        assertEquals(today, task?.dueDate, "a stopped series must not advance its due date")
    }

    @Test
    fun `a series with no end date keeps recurring`() = runTest {
        repo.seed(recurringTask(dueDate = today, spec = weekly))

        useCase()(TaskId("t1"))

        val task = repo.get(TaskId("t1"))
        assertNull(task?.archivedAt)
        assertEquals(today.plus(7, DateTimeUnit.DAY), task?.dueDate)
    }

    @Test
    fun `a null end date inside a termination is unbounded`() = runTest {
        repo.seed(
            recurringTask(
                dueDate = today,
                spec = weekly.copy(termination = RecurrenceTermination(endDate = null)),
            ),
        )

        useCase()(TaskId("t1"))

        assertNull(repo.get(TaskId("t1"))?.archivedAt)
    }

    // ─── Every base, not just the default one ─────────────────────────────────

    @Test
    fun `all three bases stop at the end date`() = runTest {
        val bases = listOf(RecurrenceBase.FROM_DUE, RecurrenceBase.FROM_COMPLETION, RecurrenceBase.CATCH_UP)
        bases.forEachIndexed { index, base ->
            val id = TaskId("bounded-$index")
            // End one day before the next occurrence, whatever the base.
            repo.seed(
                recurringTask(
                    dueDate = today,
                    spec = RecurrenceSpec.Interval(base, 1, DateTimeUnit.WEEK)
                        .copy(termination = RecurrenceTermination(today.plus(6, DateTimeUnit.DAY))),
                ).copy(id = id),
            )

            useCase()(id)

            assertNotNull(repo.get(id)?.archivedAt, "base $base must stop past its end date")
        }
    }

    @Test
    fun `catch-up still creates historical copies before the series stops`() = runTest {
        // Due two weeks ago, weekly, catch-up base. The missed occurrences are
        // real work that already happened — only the roll-forward is suppressed.
        val staleDue = today.plus(-14, DateTimeUnit.DAY)
        repo.seed(
            recurringTask(
                dueDate = staleDue,
                spec = RecurrenceSpec.Interval(RecurrenceBase.CATCH_UP, 1, DateTimeUnit.WEEK)
                    .copy(termination = RecurrenceTermination(staleDue.plus(6, DateTimeUnit.DAY))),
            ),
        )

        useCase()(TaskId("t1"))

        val all = repo.tasks.value.values.toList()
        assertTrue(all.size > 1, "catch-up copies must still be created, got ${all.size}")
        assertNotNull(repo.get(TaskId("t1"))?.archivedAt, "the rolling task stops")
    }

    // ─── Value semantics — the reason termination is a constructor property ───

    @Test
    fun `two specs differing only by end date are not equal`() {
        val bounded = weekly.copy(termination = RecurrenceTermination(today))
        assertNotEquals(weekly, bounded)
        assertNotEquals(bounded.hashCode(), weekly.hashCode())
    }

    @Test
    fun `copy preserves the termination`() {
        // A `var` on the sealed base would be invisible to copy() and silently
        // dropped here. This is the assertion that keeps it a constructor property.
        val bounded = weekly.copy(termination = RecurrenceTermination(today))
        val changed = bounded.copy(amount = 2)

        assertEquals(bounded.termination, changed.termination)
        assertEquals(2, changed.amount)
    }

    @Test
    fun `every variant carries a termination`() {
        val term = RecurrenceTermination(today)
        val specs = listOf(
            RecurrenceSpec.Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.WEEK, term),
            RecurrenceSpec.Weekly(RecurrenceBase.FROM_DUE, setOf(1), term),
            RecurrenceSpec.Monthly(RecurrenceBase.FROM_DUE, 15, term),
            RecurrenceSpec.Yearly(RecurrenceBase.FROM_DUE, 6, 15, term),
        )
        specs.forEach { assertEquals(term, it.termination, "${it::class.simpleName} lost its termination") }
        assertTrue(specs.all { it.isExhaustedBy(today.plus(1, DateTimeUnit.DAY)) })
    }

    // ─── Storage — no migration, old rules keep decoding ──────────────────────

    @Test
    fun `a rule written before this field decodes with no termination`() {
        // A literal blob in the exact shape a pre-change build wrote — no
        // `termination` key at all. Derived from the encoder it would only prove
        // the encoder agrees with itself; this proves an *old row* still loads.
        val legacy = """{"_type":"com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval",""" +
            """"base":"FROM_DUE","amount":1,"unit":{"_type":"kotlinx.datetime.DayBased","days":7}}"""

        val decoded = StableJson.decodeFromString(RecurrenceSpec.serializer(), legacy)

        assertNull(decoded.termination, "a stored rule without the field must decode as unbounded")
        assertEquals(1, (decoded as RecurrenceSpec.Interval).amount)
    }

    @Test
    fun `a bounded rule round-trips through storage`() {
        val bounded = weekly.copy(termination = RecurrenceTermination(today))
        val encoded = StableJson.encodeToString(RecurrenceSpec.serializer(), bounded)
        val decoded = StableJson.decodeFromString(RecurrenceSpec.serializer(), encoded)

        assertEquals(bounded, decoded)
        assertEquals(RecurrenceTermination(today), decoded.termination)
    }

    @Test
    fun `an unbounded rule carries no termination wrapper`() {
        // kotlinx does emit `"termination":null` for the null property, and that
        // is harmless. What must not appear is an empty wrapper object, which
        // would read as "has a termination that says nothing".
        val encoded = StableJson.encodeToString(RecurrenceSpec.serializer(), weekly)
        assertFalse(
            encoded.contains("\"termination\":{"),
            "an unbounded series must not carry an empty termination wrapper, got $encoded",
        )
        assertNull(StableJson.decodeFromString(RecurrenceSpec.serializer(), encoded).termination)
    }
}
