package com.singularity.todo.feature.tasks.domain

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.logic.TaskSeriesPlanner
import com.singularity.todo.feature.tasks.domain.model.CreateTaskInput
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for [TaskSeriesPlanner] — the plan of a series.
 *
 * Pure domain: no database, no repository, no Compose.
 */
@Tag("fast")
class TaskSeriesPlannerTest {

    private val userId = UserId("test-user")
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private val base = CreateTaskInput(
        title = "Pay the rent",
        description = null,
        priority = TaskPriority.High,
        kind = TaskKind.Task,
        projectId = null,
        parentTaskId = null,
        tagIds = emptyList(),
        dueDate = null,
        dueTime = LocalTime(9, 0),
        startDate = null,
        startTime = null,
        endDate = null,
        endTime = null,
        accentColor = null,
        emoji = null,
        someday = false,
    )

    // ─── One date is an ordinary task ──────────────────────────────────────

    @Test
    fun `one date produces one task with no series id`() {
        val plan = TaskSeriesPlanner.plan(
            base = base,
            dates = listOf(LocalDate(2026, 10, 15)),
            now = now,
            userId = userId,
        )

        assertEquals(1, plan.size)
        assertNull(plan.first().seriesId, "a single date is not a series")
        assertEquals(LocalDate(2026, 10, 15), plan.first().dueDate)
    }

    @Test
    fun `no date produces one undated task`() {
        val plan = TaskSeriesPlanner.plan(base = base, dates = emptyList(), now = now, userId = userId)

        assertEquals(1, plan.size)
        assertNull(plan.first().dueDate)
        assertNull(plan.first().seriesId)
    }

    // ─── Several dates share one id ────────────────────────────────────────

    @Test
    fun `three dates produce three tasks with one shared series id`() {
        val seriesId = TaskSeriesId("series-1")
        val plan = TaskSeriesPlanner.plan(
            base = base,
            dates = listOf(
                LocalDate(2026, 10, 15),
                LocalDate(2026, 10, 5),
                LocalDate(2026, 11, 3),
            ),
            seriesId = seriesId,
            now = now,
            userId = userId,
        )

        assertEquals(3, plan.size)
        assertTrue(plan.all { it.seriesId == seriesId }, "every member carries the same id")
    }

    @Test
    fun `the plan is in date order whatever order the dates came in`() {
        val plan = TaskSeriesPlanner.plan(
            base = base,
            dates = listOf(
                LocalDate(2026, 11, 3),
                LocalDate(2026, 10, 5),
                LocalDate(2026, 10, 15),
            ),
            now = now,
            userId = userId,
        )

        val planDates = plan.mapNotNull { it.dueDate }
        assertEquals(
            listOf(LocalDate(2026, 10, 5), LocalDate(2026, 10, 15), LocalDate(2026, 11, 3)),
            planDates,
        )
    }

    @Test
    fun `the same date twice is one occurrence`() {
        val plan = TaskSeriesPlanner.plan(
            base = base,
            dates = listOf(LocalDate(2026, 10, 15), LocalDate(2026, 10, 15)),
            now = now,
            userId = userId,
        )

        assertEquals(1, plan.size)
        assertNull(plan.first().seriesId)
    }

    @Test
    fun `every member keeps the shared fields`() {
        val plan = TaskSeriesPlanner.plan(
            base = base,
            dates = listOf(LocalDate(2026, 10, 15), LocalDate(2026, 10, 17)),
            now = now,
            userId = userId,
        )

        assertTrue(plan.all { it.title == "Pay the rent" })
        assertTrue(plan.all { it.priority == TaskPriority.High })
        assertTrue(plan.all { it.dueTime == LocalTime(9, 0) })
    }

    @Test
    fun `no member carries a repeat rule`() {
        val plan = TaskSeriesPlanner.plan(
            base = base,
            dates = listOf(LocalDate(2026, 10, 15), LocalDate(2026, 10, 17)),
            now = now,
            userId = userId,
        )

        assertTrue(plan.all { it.recurrence == null }, "a series never holds a rule")
    }

    // ─── The active window travels with the date ───────────────────────────

    @Test
    fun `the window keeps its offset from the date`() {
        val withWindow = base.copy(
            dueDate = LocalDate(2026, 10, 15),
            startDate = LocalDate(2026, 10, 12),
            endDate = LocalDate(2026, 10, 14),
        )
        val plan = TaskSeriesPlanner.plan(
            base = withWindow,
            dates = listOf(LocalDate(2026, 10, 15), LocalDate(2026, 10, 17)),
            now = now,
            userId = userId,
        )

        assertEquals(LocalDate(2026, 10, 12), plan[0].startDate)
        assertEquals(LocalDate(2026, 10, 14), plan[0].endDate)
        assertEquals(LocalDate(2026, 10, 14), plan[1].startDate)
        assertEquals(LocalDate(2026, 10, 16), plan[1].endDate)
    }

    // ─── The ceiling ───────────────────────────────────────────────────────

    @Test
    fun `thirty dates are allowed`() {
        val dates = (1..30).map { LocalDate(2026, 10, 1).plusDays(it) }
        val plan = TaskSeriesPlanner.plan(base = base, dates = dates, now = now, userId = userId)

        assertEquals(30, plan.size)
    }

    @Test
    fun `thirty-one dates are refused`() {
        val dates = (0..30).map { LocalDate(2026, 10, 1).plusDays(it) }
        assertFailsWith<IllegalArgumentException> {
            TaskSeriesPlanner.plan(base = base, dates = dates, now = now, userId = userId)
        }
    }

    // ─── The rows are distinct ─────────────────────────────────────────────

    @Test
    fun `every member has its own id`() {
        val plan = TaskSeriesPlanner.plan(
            base = base,
            dates = listOf(LocalDate(2026, 10, 15), LocalDate(2026, 10, 17)),
            now = now,
            userId = userId,
        )

        assertNotEquals(plan[0].id, plan[1].id, "two occurrences are two tasks")
    }
}

/** `LocalDate` plus a number of days, without pulling in a date library. */
private fun LocalDate.plusDays(days: Int): LocalDate =
    LocalDate.fromEpochDays(this.toEpochDays() + days)
