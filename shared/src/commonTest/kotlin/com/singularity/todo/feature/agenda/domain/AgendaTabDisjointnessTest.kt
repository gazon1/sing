package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.schedule.WorkingDays
import com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.AgendaSeed
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the contract of the three bottom-bar agenda tabs: they are a **partition**
 * of the tasks, not three nested views — on a day off *and* on a working day
 * with work hours configured.
 *
 * The tabs used to be nested subsets of each other — `Today` was Overdue + Today,
 * `Upcoming` was Overdue + Today + Tomorrow + This Week + Next Week, and `Inbox`
 * was all of that plus Yesterday, This Month and No Date — so with one task in
 * the database all three tabs rendered the identical screen.
 *
 * The partition:
 *
 * | Tab       | Sections                             |
 * |-----------|--------------------------------------|
 * | Inbox     | No Date                              |
 * | Today     | Overdue, Today                       |
 * | Upcoming  | Tomorrow, This Week, Next Week       |
 *
 * Two things make it hold. The tabs own disjoint sets of date buckets, so no
 * section is shown by two of them; and the Upcoming tab's "This Week" section is
 * the week bucket *minus* the days the Today tab owns, because the week bucket
 * spans Monday to Sunday and would otherwise hand today's and the overdue tasks
 * to two tabs at once.
 *
 * The configuration that used to break it is the working day: the Today tab
 * rendered the work-day plan *instead of* its own buckets, so an overdue task
 * and today's untimed tasks were in no tab at all. The plan is now layered
 * inside the tab — Overdue, then the work blocks, then the rest of today — and
 * every equality below runs under both configurations
 * ([configurations]). See ADR
 * `2026-11-05-the-work-day-plan-sits-inside-the-today-tab`.
 *
 * See ADR `2026-10-31-the-agenda-tabs-are-disjoint`.
 */
@Tag("fast")
class AgendaTabDisjointnessTest {
    /** Reference "today" — a Wednesday, so both ends of the week are in reach. */
    private val today: LocalDate = AgendaSeed.TODAY

    private val now = kotlin.time.Instant.fromEpochMilliseconds(0)

    /**
     * The work day a user configured: 09:00–17:00 with lunch at 12:00–13:00.
     *
     * Narrower than the date on purpose, so a task due today at 07:00 is today's
     * work and yet belongs to no work block.
     */
    private val workDay =
        AgendaPresets.workDayPreset(
            dayStartMinutes = 540,
            dayEndMinutes = 1020,
            lunchStartMinutes = 720,
            lunchEndMinutes = 780,
        )

    /** The two configurations the contract has to hold in. */
    private val configurations: List<Pair<Boolean, String>> =
        listOf(
            false to "a day off",
            true to "a working day",
        )

    /**
     * The three bottom-bar tabs as the user meets them on [workingDay].
     *
     * [today] is a Wednesday, so it is a working day under [WorkingDays.DEFAULT];
     * the day-off configuration is asked about a Saturday instead.
     */
    private fun tabs(workingDay: Boolean): List<Pair<String, AgendaDefinition>> {
        val todayTab =
            AgendaPresets.forToday(
                dayOfWeek = if (workingDay) today.dayOfWeek else DayOfWeek.SATURDAY,
                workingDays = WorkingDays.DEFAULT,
                workDay = workDay,
            )
        return listOf(
            "Inbox" to AgendaPresets.Inbox,
            "Today" to todayTab,
            "Upcoming" to AgendaPresets.Upcoming,
        )
    }

    // ─── Task factory ──────────────────────────────────────────────────────

    private fun task(
        id: String,
        dueDate: LocalDate?,
        dueTime: LocalTime? = null,
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
            dueTime = dueTime,
            startDate = null,
            startTime = null,
            endDate = null,
            endTime = null,
            accentColor = null,
            emoji = null,
            estimateMinutes = null,
            completedAt = null,
            someday = false,
            archivedAt = null,
            isPinned = false,
            blockedBy = emptySet(),
            aiSuppressedTagIds = emptySet(),
            recurrence = null,
            createdAt = now,
            updatedAt = now,
            userId = UserId(AgendaSeed.PERSONAL_USER_ID),
            serverVersion = 0,
            hlc = null,
        )

    // ─── Helpers ───────────────────────────────────────────────────────────

    /** The names of the tabs in [tabs] whose rendered sections contain [task]. */
    private fun tabsContaining(
        task: Task,
        tabs: List<Pair<String, AgendaDefinition>>,
    ): List<String> =
        tabs
            .filter { (_, definition) ->
                AgendaEvaluator
                    .evaluate(listOf(task), definition, today, now)
                    .any { section -> section.tasks.any { it.task.id == task.id } }
            }.map { (name, _) -> name }

    /** The names of the sections of [definition] that contain [task]. */
    private fun sectionsContaining(
        definition: AgendaDefinition,
        task: Task,
    ): List<String> =
        AgendaEvaluator
            .evaluate(listOf(task), definition, today, now)
            .filter { section -> section.tasks.any { it.task.id == task.id } }
            .map { it.name }

    /** The Today tab as the user meets it on [workingDay]. */
    private fun todayTab(workingDay: Boolean): AgendaDefinition = tabs(workingDay).first { it.first == "Today" }.second

    /**
     * One task the partition has to place, and the tab that owns it.
     *
     * [expectedTab] is null for the cases that are about *where inside* a tab a
     * task lands rather than about which tab owns it.
     */
    private data class Case(
        val label: String,
        val dueDate: LocalDate?,
        val dueTime: LocalTime? = null,
        val expectedTab: String? = null,
    )

    // ─── The contract ──────────────────────────────────────────────────────

    @Test
    fun `no two tabs declare the same section`() {
        configurations.forEach { (workingDay, label) ->
            // A name may repeat inside one tab — the working day declares "Work"
            // twice, for the morning and the afternoon — but never across two
            // tabs: that is the same task offered under the same header twice.
            val ownersByName =
                tabs(workingDay)
                    .flatMap { (tab, definition) -> definition.sections.map { section -> section.name to tab } }
                    .groupBy({ (name, _) -> name }, { (_, tab) -> tab })
            val shared =
                ownersByName
                    .mapValues { (_, owners) -> owners.distinct() }
                    .filterValues { owners -> owners.size > 1 }

            assertEquals(
                emptyMap(),
                shared,
                "on $label the tabs must not share a section — a section shown by two tabs " +
                    "shows the same task under two headers",
            )
        }
    }

    @Test
    fun `the work blocks are layers inside the today tab, not a replacement for it`() {
        assertEquals(
            listOf("Overdue", "Today"),
            todayTab(workingDay = false).sections.map { it.name },
            "a day off shows the tab's own buckets and nothing else",
        )
        val workDaySections = todayTab(workingDay = true).sections.sortedBy { it.order }
        assertEquals(
            listOf("Overdue", "Work", "Lunch", "Work", "Today"),
            workDaySections.map { it.name },
            "a working day keeps the tab's buckets and puts the work blocks between them",
        )
        assertEquals(
            Selector.DateBucket(RelativeBucket.Overdue),
            workDaySections.first().selector,
            "the work-day tab must lead with the Overdue bucket, not depend on a section id string",
        )
    }

    @Test
    fun `every task within the tabs' horizon appears in exactly one tab`() {
        val cases =
            listOf(
                Case("undated", null, expectedTab = "Inbox"),
                Case("overdue", LocalDate(2026, 10, 9), expectedTab = "Today"),
                Case("overdue at 10:00", LocalDate(2026, 10, 9), LocalTime(10, 0), "Today"),
                Case("yesterday", LocalDate(2026, 10, 13), expectedTab = "Today"),
                Case("today, untimed", LocalDate(2026, 10, 14), expectedTab = "Today"),
                Case("today at 10:00", LocalDate(2026, 10, 14), LocalTime(10, 0), "Today"),
                Case("today at 12:30", LocalDate(2026, 10, 14), LocalTime(12, 30), "Today"),
                Case("today at 15:00", LocalDate(2026, 10, 14), LocalTime(15, 0), "Today"),
                Case("today at 07:00", LocalDate(2026, 10, 14), LocalTime(7, 0), "Today"),
                Case("tomorrow", LocalDate(2026, 10, 15), expectedTab = "Upcoming"),
                Case("tomorrow at 10:00", LocalDate(2026, 10, 15), LocalTime(10, 0), "Upcoming"),
                Case("later this week", LocalDate(2026, 10, 16), expectedTab = "Upcoming"),
                Case("end of this week", LocalDate(2026, 10, 18), expectedTab = "Upcoming"),
                Case("start of next week", LocalDate(2026, 10, 19), expectedTab = "Upcoming"),
                Case("end of next week", LocalDate(2026, 10, 25), expectedTab = "Upcoming"),
            )

        configurations.forEach { (workingDay, label) ->
            val tabSet = tabs(workingDay)
            cases.forEachIndexed { index, case ->
                val task = task("t$index", case.dueDate, case.dueTime)

                assertEquals(
                    listOf(case.expectedTab),
                    tabsContaining(task, tabSet),
                    "on $label, a task ${case.label} must be in the ${case.expectedTab} tab and in no other",
                )
            }
        }
    }

    @Test
    fun `a task appears in exactly one section of its tab`() {
        val cases =
            listOf(
                Case("undated", null, expectedTab = "Inbox"),
                Case("yesterday", LocalDate(2026, 10, 13), expectedTab = "Today"),
                Case("today, untimed", LocalDate(2026, 10, 14), expectedTab = "Today"),
                Case("today at 10:00", LocalDate(2026, 10, 14), LocalTime(10, 0), "Today"),
                Case("overdue at 10:00", LocalDate(2026, 10, 9), LocalTime(10, 0), "Today"),
                Case("tomorrow", LocalDate(2026, 10, 15), expectedTab = "Upcoming"),
                Case("later this week", LocalDate(2026, 10, 16), expectedTab = "Upcoming"),
                Case("end of next week", LocalDate(2026, 10, 25), expectedTab = "Upcoming"),
            )

        configurations.forEach { (workingDay, label) ->
            val tabSet = tabs(workingDay)
            cases.forEachIndexed { index, case ->
                val definition = tabSet.first { it.first == case.expectedTab }.second
                val task = task("t$index", case.dueDate, case.dueTime)
                val sections = sectionsContaining(definition, task)

                assertEquals(
                    1,
                    sections.size,
                    "on $label, a task ${case.label} must be in one section of the " +
                        "${case.expectedTab} tab, not in $sections",
                )
            }
        }
    }

    /**
     * Where the working day puts today inside the tab: a task's own hour decides
     * its block, and anything the work hours do not cover still lands in "Today".
     */
    @Test
    fun `on a working day today's tasks land in their work block or in Today`() {
        val tab = todayTab(workingDay = true)
        val cases =
            listOf(
                Triple("today at 10:00", LocalTime(10, 0), "Work"),
                Triple("today at 12:30", LocalTime(12, 30), "Lunch"),
                Triple("today at 15:00", LocalTime(15, 0), "Work"),
                Triple("today at 07:00, before the work day", LocalTime(7, 0), "Today"),
                Triple("today at 18:00, after the work day", LocalTime(18, 0), "Today"),
                Triple("today, untimed", null, "Today"),
            )

        cases.forEachIndexed { index, (label, dueTime, expectedSection) ->
            val task = task("t$index", LocalDate(2026, 10, 14), dueTime)

            assertEquals(
                listOf(expectedSection),
                sectionsContaining(tab, task),
                "a task $label must be in \"$expectedSection\", not in " +
                    sectionsContaining(tab, task),
            )
        }
    }

    @Test
    fun `on a working day an overdue task is still in Overdue, timed or not`() {
        val tab = todayTab(workingDay = true)
        val overdueDate = LocalDate(2026, 10, 9)

        assertEquals(
            listOf("Overdue"),
            sectionsContaining(tab, task("overdue", overdueDate)),
            "an untimed overdue task belongs to the Overdue section",
        )
        assertEquals(
            listOf("Overdue"),
            sectionsContaining(tab, task("overdue-timed", overdueDate, LocalTime(10, 0))),
            "an overdue task with a time is still overdue, not part of today's plan",
        )
    }

    @Test
    fun `a timed task due on another day is not pulled into today's work plan`() {
        val tab = todayTab(workingDay = true)

        assertEquals(
            emptyList(),
            sectionsContaining(tab, task("tomorrow", LocalDate(2026, 10, 15), LocalTime(10, 0))),
            "a timed task due tomorrow belongs to Upcoming, not to today's work block",
        )
        assertEquals(
            emptyList(),
            sectionsContaining(tab, task("next-month", LocalDate(2026, 11, 20), LocalTime(10, 0))),
            "a timed task due next month is not part of today's plan either",
        )
    }

    @Test
    fun `the undated task is in the Inbox tab and in no other`() {
        configurations.forEach { (workingDay, label) ->
            val task = task("task-undated", null)

            assertEquals(
                listOf("Inbox"),
                tabsContaining(task, tabs(workingDay)),
                "on $label an undated task belongs to the Inbox tab — the tab named for it",
            )
        }
        assertEquals(
            listOf("No Date"),
            sectionsContaining(AgendaPresets.Inbox, task("task-undated", null)),
            "the Inbox tab shows the undated tasks under \"No Date\"",
        )
    }

    /**
     * The decision, pinned: a task dated further out than next week is in no
     * bottom-bar tab at all — and that is deliberate.
     *
     * The three tabs are a partition of what they cover, not of every task: they
     * answer "what is not dated yet", "what is now or late" and "what is coming
     * soon". A task due in three weeks is none of those; it is planned, and the
     * Plans timeline, the Calendar and a saved view seeded from
     * [AgendaPresets.All] are where it is seen. The wide view still holds it, so
     * the gap is a horizon rather than a hole.
     *
     * See ADR `2026-11-05-the-agenda-tabs-cover-a-horizon`. Keeping this test is
     * the acceptance criterion: the gap must stay a stated decision, not become
     * an accidental regression in either direction.
     */
    @Test
    fun `a task due beyond next week is in no tab`() {
        val task = task("task-beyond", LocalDate(2026, 10, 26))

        configurations.forEach { (workingDay, label) ->
            assertEquals(
                emptyList(),
                tabsContaining(task, tabs(workingDay)),
                "on $label a task dated beyond next week is in no agenda tab — the tabs cover a " +
                    "horizon, and the answer for \"what is due later\" is the Plans timeline and the Calendar",
            )
        }
        assertEquals(
            listOf("This Month"),
            sectionsContaining(AgendaPresets.All, task),
            "the wide view is where a later task is seen, so the tab bar's horizon " +
                "must not drop it from the agenda altogether",
        )
    }

    /**
     * The wide view is not a tab: it is the seed a new saved view starts from.
     *
     * It holds every section the tabs show — it is the whole agenda — and it
     * holds buckets the three tabs deliberately leave out, which is what makes
     * it the wide view rather than a fourth copy of one of them.
     */
    @Test
    fun `the All preset is the wide view, not a tab`() {
        val tabSections =
            tabs(workingDay = false)
                .flatMap { (_, definition) -> definition.sections.map { it.name } }
                .toSet()
        val allSections = AgendaPresets.All.sections.map { it.name }

        tabSections.forEach { section ->
            assertTrue(
                section in allSections,
                "the wide All view must still hold the \"$section\" section",
            )
        }
        assertTrue(
            allSections.any { it !in tabSections },
            "the wide All view must hold buckets the tabs do not — it is the whole " +
                "agenda, not a fourth tab",
        )
    }
}
