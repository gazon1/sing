package com.singularity.todo.feature.plans.domain

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.plans.domain.logic.TimelineGrouper
import com.singularity.todo.feature.plans.domain.logic.TimelineLabels
import com.singularity.todo.feature.plans.domain.model.PlansGranularity
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the grouping of open work into timeline sections.
 *
 * The invariant under test is the one the screen rests on: a task is rendered
 * in exactly one section. A task shown twice is a row the user cannot act on
 * once, and in this app it was also the crash the agenda hit when two
 * sections overlapped.
 */
@Tag("fast")
class TimelineGrouperTest {

    // Wednesday 2026-09-16 — mid-week, so a week boundary is in reach.
    private val today = LocalDate(2026, Month.SEPTEMBER, 16)

    private val tomorrow = today.plus(1, DateTimeUnit.DAY)
    private val inSixDays = today.plus(6, DateTimeUnit.DAY)
    private val yesterday = today.minus(1, DateTimeUnit.DAY)

    @Test
    fun `days appear in chronological order, each holding that day's tasks`() {
        val sections = TimelineGrouper.group(
            tasks = listOf(
                task("later", "Later", due = inSixDays),
                task("soon", "Soon", due = tomorrow),
                task("now", "Now", due = today),
            ),
            granularity = PlansGranularity.Day,
            today = today,
        )

        assertEquals(listOf("Today", "Tomorrow", "Tue, 22 Sep"), sections.map { it.label })
        assertEquals(listOf("now"), sections[0].tasks.map { it.task.id.value })
        assertEquals(listOf("soon"), sections[1].tasks.map { it.task.id.value })
        assertEquals(listOf("later"), sections[2].tasks.map { it.task.id.value })
    }

    @Test
    fun `a task lands in exactly one section`() {
        val sections = TimelineGrouper.group(
            tasks = listOf(task("one", "One", due = tomorrow)),
            granularity = PlansGranularity.Day,
            today = today,
        )

        assertEquals(1, sections.sumOf { it.tasks.size })
    }

    @Test
    fun `a task stays in exactly one section at every granularity`() {
        val tasks = listOf(task("one", "One", due = tomorrow))

        for (granularity in PlansGranularity.entries) {
            val sections = TimelineGrouper.group(tasks, granularity, today)
            assertEquals(
                1,
                sections.sumOf { it.tasks.size },
                "the task must appear once at $granularity",
            )
        }
    }

    @Test
    fun `a period with no tasks is not a section`() {
        val sections = TimelineGrouper.group(
            tasks = listOf(task("now", "Now", due = today)),
            granularity = PlansGranularity.Day,
            today = today,
        )

        assertEquals(1, sections.size)
        assertEquals("Today", sections[0].label)
    }

    @Test
    fun `overdue work leads the timeline and is in no dated section`() {
        val sections = TimelineGrouper.group(
            tasks = listOf(
                task("late", "Late", due = yesterday),
                task("now", "Now", due = today),
            ),
            granularity = PlansGranularity.Day,
            today = today,
        )

        assertEquals(TimelineLabels.OVERDUE, sections.first().label)
        assertEquals(listOf("late"), sections.first().tasks.map { it.task.id.value })
        assertEquals(1, sections.sumOf { it.tasks.count { row -> row.task.id.value == "late" } })
    }

    @Test
    fun `a completed task is not on the timeline`() {
        val sections = TimelineGrouper.group(
            tasks = listOf(task("done", "Done", due = today, completed = true)),
            granularity = PlansGranularity.Day,
            today = today,
        )

        assertTrue(sections.isEmpty())
    }

    @Test
    fun `a task without a due date is not on the timeline`() {
        val sections = TimelineGrouper.group(
            tasks = listOf(task("undated", "Undated")),
            granularity = PlansGranularity.Day,
            today = today,
        )

        assertTrue(sections.isEmpty())
    }

    @Test
    fun `a task beyond the horizon is not on the timeline`() {
        val sections = TimelineGrouper.group(
            tasks = listOf(task("far", "Far", due = today.plus(45, DateTimeUnit.DAY))),
            granularity = PlansGranularity.Day,
            today = today,
        )

        assertTrue(sections.isEmpty())
    }

    @Test
    fun `a task beyond the day horizon appears at the month granularity`() {
        val far = today.plus(45, DateTimeUnit.DAY)

        assertTrue(
            TimelineGrouper
                .group(listOf(task("far", "Far", due = far)), PlansGranularity.Day, today)
                .isEmpty(),
        )
        assertEquals(
            1,
            TimelineGrouper
                .group(listOf(task("far", "Far", due = far)), PlansGranularity.Month, today)
                .sumOf { it.tasks.size },
        )
    }

    @Test
    fun `weeks group Monday to Sunday`() {
        // The next week runs Mon 2026-09-21 .. Sun 2026-09-27. The current week
        // is not used: it starts before today, and a task due in it is overdue
        // rather than scheduled.
        val sections = TimelineGrouper.group(
            tasks = listOf(
                task("mon", "Monday", due = LocalDate(2026, Month.SEPTEMBER, 21)),
                task("sun", "Sunday", due = LocalDate(2026, Month.SEPTEMBER, 27)),
            ),
            granularity = PlansGranularity.Week,
            today = today,
        )

        assertEquals(1, sections.size)
        assertEquals("21–27 Sep", sections[0].label)
        assertEquals(listOf("mon", "sun"), sections[0].tasks.map { it.task.id.value })
    }

    @Test
    fun `a task due earlier in the current week is overdue, not in that week`() {
        // The current week runs Mon 2026-09-14 .. Sun 2026-09-20, and today is
        // the Wednesday inside it. A task due on the Monday has a date that has
        // passed, so it leads the timeline instead of sitting in the week.
        val sections = TimelineGrouper.group(
            tasks = listOf(task("mon", "Monday", due = LocalDate(2026, Month.SEPTEMBER, 14))),
            granularity = PlansGranularity.Week,
            today = today,
        )

        assertEquals(listOf(TimelineLabels.OVERDUE), sections.map { it.label })
        assertEquals(listOf("mon"), sections[0].tasks.map { it.task.id.value })
    }

    @Test
    fun `months group by calendar month`() {
        val sections = TimelineGrouper.group(
            tasks = listOf(
                task("early", "Early", due = LocalDate(2026, Month.SEPTEMBER, 20)),
                task("late", "Late", due = LocalDate(2026, Month.SEPTEMBER, 30)),
                task("next", "Next", due = LocalDate(2026, Month.OCTOBER, 1)),
            ),
            granularity = PlansGranularity.Month,
            today = today,
        )

        assertEquals(listOf("September 2026", "October 2026"), sections.map { it.label })
        assertEquals(listOf("early", "late"), sections[0].tasks.map { it.task.id.value })
        assertEquals(listOf("next"), sections[1].tasks.map { it.task.id.value })
    }

    @Test
    fun `an overdue task carries the overdue badge`() {
        val sections = TimelineGrouper.group(
            tasks = listOf(task("late", "Late", due = yesterday)),
            granularity = PlansGranularity.Day,
            today = today,
        )

        assertEquals(
            com.singularity.todo.feature.tasks.domain.model.TaskBadge.Overdue,
            sections[0].tasks[0].badge,
        )
    }

    private fun task(
        id: String,
        title: String,
        due: LocalDate? = null,
        completed: Boolean = false,
    ): Task = Task(
        id = TaskId(id),
        title = title,
        dueDate = due,
        kind = TaskKind.Task,
        completedAt = if (completed) kotlin.time.Instant.fromEpochMilliseconds(0) else null,
        createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        updatedAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        userId = UserId("test-user"),
    )
}
