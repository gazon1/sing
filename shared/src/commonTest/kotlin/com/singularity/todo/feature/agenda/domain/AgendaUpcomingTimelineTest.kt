package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator
import com.singularity.todo.feature.agenda.domain.logic.AgendaGroupLabels
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.DateGrouping
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.model.agenda
import com.singularity.todo.feature.tasks.domain.TaskDomain
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Pins the Upcoming tab's shape: the whole future, grouped by day, week or
 * month, with no horizon and no completed work.
 *
 * The tab used to hold three fixed buckets and stop at next week, leaving later
 * tasks to the Plans timeline. It now holds the whole future and renders the
 * periods that carry a task — not a calendar page with a header per empty day.
 *
 * See ADR `2026-11-11-upcoming-owns-the-whole-future`.
 */
@Tag("fast")
class AgendaUpcomingTimelineTest {
    /** A Wednesday in the middle of a month. */
    private val today: LocalDate = LocalDate(2026, 10, 14)

    private val now = Instant.fromEpochMilliseconds(0)

    private fun task(
        id: String,
        dueDate: LocalDate?,
        completedAt: Instant? = null,
    ): Task =
        Task(
            id = TaskId(id),
            title = "Task $id",
            description = null,
            priority = TaskPriority.None,
            kind = TaskKind.Task,
            projectId = null,
            parentTaskId = null,
            tags = emptyList(),
            dueDate = dueDate,
            dueTime = null,
            startDate = null,
            startTime = null,
            endDate = null,
            endTime = null,
            accentColor = null,
            emoji = null,
            estimateMinutes = null,
            completedAt = completedAt,
            someday = false,
            archivedAt = null,
            isPinned = false,
            blockedBy = emptySet(),
            aiSuppressedTagIds = emptySet(),
            recurrence = null,
            createdAt = now,
            updatedAt = now,
            userId = UserId("profile-personal"),
            serverVersion = 0,
            hlc = null,
        )

    private fun sections(
        tasks: List<Task>,
        grouping: DateGrouping? = null,
    ) = AgendaEvaluator.evaluate(tasks, AgendaPresets.Upcoming, today, now, dateGrouping = grouping)

    @Test
    fun `the feed holds the whole future grouped by date and leaves the past to Today`() {
        val tasks =
            listOf(
                task("overdue", LocalDate(2026, 10, 9)),
                task("today", LocalDate(2026, 10, 14)),
                task("undated", null),
                task("tomorrow", LocalDate(2026, 10, 15)),
                task("this-week", LocalDate(2026, 10, 18)),
                task("next-month", LocalDate(2026, 11, 20)),
                task("distant", LocalDate(2027, 3, 1)),
            )

        val sections = sections(tasks)

        assertEquals(
            listOf("Tomorrow", "Sun, 18 Oct", "Fri, 20 Nov", "Mon, 1 Mar 2027"),
            sections.map { it.name },
            "one header per date that holds a task, in chronological order",
        )
        val inFeed = sections.flatMap { section -> section.tasks.map { it.task.id.value } }.toSet()
        assertEquals(
            setOf("tomorrow", "this-week", "next-month", "distant"),
            inFeed,
            "the feed starts tomorrow: overdue, today and undated tasks belong to Today and Inbox",
        )
    }

    @Test
    fun `every task on one date is in that date's section`() {
        val tasks =
            listOf(
                task("first", LocalDate(2026, 10, 15)),
                task("second", LocalDate(2026, 10, 15)),
                task("third", LocalDate(2026, 10, 15)),
            )

        val section = sections(tasks).single()

        assertEquals("Tomorrow", section.name)
        assertEquals(3, section.badge)
        assertEquals(listOf("first", "second", "third"), section.tasks.map { it.task.id.value })
    }

    @Test
    fun `a date with no task is not a header`() {
        val tasks =
            listOf(
                task("tomorrow", LocalDate(2026, 10, 15)),
                task("next-week", LocalDate(2026, 10, 21)),
            )

        val sections = sections(tasks)

        assertEquals(
            listOf("Tomorrow", "Wed, 21 Oct"),
            sections.map { it.name },
            "the six days between the two plans are a gap, not six empty sections",
        )
    }

    @Test
    fun `a dated section prefills its own date`() {
        val due = LocalDate(2026, 11, 20)

        val section = sections(listOf(task("next-month", due))).single()

        assertEquals(
            due,
            section.prefill?.dueDate,
            "the '+' of a date header must create a task due on that date",
        )
        assertEquals("${AgendaPresets.Upcoming.sections.single().effectiveId}:day:$due", section.prefill?.sectionId)
    }

    @Test
    fun `week grouping keeps every task and prefills the first future day of the period`() {
        val tasks =
            listOf(
                task("thursday", LocalDate(2026, 10, 15)),
                task("friday", LocalDate(2026, 10, 16)),
                task("next-monday", LocalDate(2026, 10, 19)),
                task("next-wednesday", LocalDate(2026, 10, 21)),
            )

        val sections = sections(tasks, DateGrouping.Week)

        assertEquals(listOf("12–18 Oct", "19–25 Oct"), sections.map { it.name })
        assertEquals(
            listOf(listOf("thursday", "friday"), listOf("next-monday", "next-wednesday")),
            sections.map { section -> section.tasks.map { it.task.id.value } },
        )
        assertEquals(
            LocalDate(2026, 10, 15),
            sections.first().prefill?.dueDate,
            "the current week's '+' must not create a task in the past",
        )
        assertEquals(
            LocalDate(2026, 10, 19),
            sections.last().prefill?.dueDate,
            "a future week's '+' creates a task on its Monday",
        )
    }

    @Test
    fun `month grouping keeps every task and prefills the first future day of the period`() {
        val tasks =
            listOf(
                task("october", LocalDate(2026, 10, 15)),
                task("november", LocalDate(2026, 11, 20)),
            )

        val sections = sections(tasks, DateGrouping.Month)

        assertEquals(listOf("October 2026", "November 2026"), sections.map { it.name })
        assertEquals(
            LocalDate(2026, 10, 15),
            sections.first().prefill?.dueDate,
            "the current month's '+' must not create a task in the past",
        )
        assertEquals(
            LocalDate(2026, 11, 1),
            sections.last().prefill?.dueDate,
            "a future month's '+' creates a task on its first day",
        )
    }

    @Test
    fun `a large future set is not truncated at any grouping`() {
        val tasks = (1..365).map { offset -> task("d$offset", today.plus(offset, DateTimeUnit.DAY)) }

        val sections = sections(tasks)

        assertEquals(365, sections.size, "each date with a task is a section; none is dropped")
        assertEquals(
            365,
            sections.flatMap { section -> section.tasks.map { it.task.id.value } }.distinct().size,
            "every future task is reachable",
        )
        assertEquals("Tomorrow", sections.first().name)
        assertEquals(
            "d365",
            sections
                .last()
                .tasks
                .single()
                .task.id.value,
        )
    }

    /**
     * The tab and the code-level `upcoming` set differ on two edges, on purpose.
     *
     * A deferred ("someday") task that still carries a future date is in the tab
     * — the tab is what the user sees — while `TaskFilter.Upcoming` and the AI's
     * `upcoming` selection leave it out. A completed future task is the reverse:
     * the tab shows work still ahead, while the filter keeps completed tasks.
     * `TaskVocabulary` declares that divergence; these tests keep the difference real.
     */
    @Test
    fun `a deferred future task is in the tab but not in the upcoming filter`() {
        val deferred = task("deferred", LocalDate(2026, 10, 15)).copy(someday = true)

        assertEquals(
            listOf("deferred"),
            sections(listOf(deferred)).flatMap { section -> section.tasks.map { it.task.id.value } },
            "the tab shows the deferred task because it is dated in the future",
        )
        assertFalse(
            TaskDomain.matchesFilter(deferred, TaskFilter.Upcoming, today),
            "the code-level upcoming set excludes deferred tasks",
        )
    }

    @Test
    fun `a completed future task leaves the feed`() {
        val completed = task("done", LocalDate(2026, 10, 15), completedAt = now)

        assertTrue(
            sections(listOf(completed)).isEmpty(),
            "the tab is work still ahead; completing a future task takes it out of the feed",
        )
    }

    @Test
    fun `a grouped section keeps an undated row instead of dropping it`() {
        val definition =
            agenda("Grouped") {
                section("all", "All", Selector.Anything, groupBy = DateGrouping.Day)
            }
        val undated = task("undated", null)

        val sections = AgendaEvaluator.evaluate(listOf(undated), definition, today, now)

        assertEquals(1, sections.size)
        assertEquals("All", sections.single().name)
        assertEquals(listOf("undated"), sections.single().tasks.map { it.task.id.value })
    }

    @Test
    fun `the day label names the near days and carries the year when it differs`() {
        assertEquals("Today", AgendaGroupLabels.day(today, today))
        assertEquals("Yesterday", AgendaGroupLabels.day(LocalDate(2026, 10, 13), today))
        assertEquals("Tomorrow", AgendaGroupLabels.day(LocalDate(2026, 10, 15), today))
        assertEquals("Sun, 18 Oct", AgendaGroupLabels.day(LocalDate(2026, 10, 18), today))
        assertTrue(
            AgendaGroupLabels.day(LocalDate(2027, 3, 1), today).endsWith("2027"),
            "a date in another year is ambiguous without the year",
        )
    }
}
