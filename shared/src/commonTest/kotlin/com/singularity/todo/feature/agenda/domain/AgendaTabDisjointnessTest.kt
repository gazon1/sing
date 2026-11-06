package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.AgendaSeed
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the contract of the three bottom-bar agenda tabs: they are a **partition**
 * of the tasks, not three nested views.
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
 * See ADR `2026-10-31-the-agenda-tabs-are-disjoint`.
 */
@Tag("fast")
class AgendaTabDisjointnessTest {
    /** Reference "today" — a Wednesday, so both ends of the week are in reach. */
    private val today: LocalDate = AgendaSeed.TODAY

    private val now = kotlin.time.Instant.fromEpochMilliseconds(0)

    /** The three bottom-bar tabs, in the order the bar shows them. */
    private val tabs: List<Pair<String, AgendaDefinition>> =
        listOf(
            "Inbox" to AgendaPresets.Inbox,
            "Today" to AgendaPresets.Today,
            "Upcoming" to AgendaPresets.Upcoming,
        )

    // ─── Task factory ──────────────────────────────────────────────────────

    private fun task(
        id: String,
        dueDate: LocalDate?,
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

    /** The names of the tabs whose rendered sections contain [task]. */
    private fun tabsContaining(task: Task): List<String> =
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

    // ─── The contract ──────────────────────────────────────────────────────

    @Test
    fun `no two tabs declare the same section`() {
        val byTab = tabs.associate { (name, definition) -> name to definition.sections.map { it.name } }

        val duplicates =
            byTab.entries
                .flatMap { (tab, sections) -> sections.map { tab to it } }
                .groupBy { (_, section) -> section }
                .filterValues { it.size > 1 }
                .map { (section, owners) -> "$section in ${owners.map { it.first }}" }

        assertEquals(
            emptyList(),
            duplicates,
            "the tabs must not share a section — a section shown by two tabs shows " +
                "the same task under two headers",
        )
    }

    @Test
    fun `every task within the tabs' horizon appears in exactly one tab`() {
        val cases =
            listOf(
                Triple("undated", null, "Inbox"),
                Triple("overdue", LocalDate(2026, 10, 9), "Today"),
                Triple("yesterday", LocalDate(2026, 10, 13), "Today"),
                Triple("today", LocalDate(2026, 10, 14), "Today"),
                Triple("tomorrow", LocalDate(2026, 10, 15), "Upcoming"),
                Triple("later this week", LocalDate(2026, 10, 16), "Upcoming"),
                Triple("end of this week", LocalDate(2026, 10, 18), "Upcoming"),
                Triple("start of next week", LocalDate(2026, 10, 19), "Upcoming"),
                Triple("end of next week", LocalDate(2026, 10, 25), "Upcoming"),
            )

        cases.forEachIndexed { index, (label, dueDate, expectedTab) ->
            val task = task("t$index", dueDate)

            assertEquals(
                listOf(expectedTab),
                tabsContaining(task),
                "a task $label must be in the $expectedTab tab and in no other",
            )
        }
    }

    @Test
    fun `a task appears in exactly one section of its tab`() {
        val cases =
            listOf(
                Triple("undated", null, "Inbox"),
                Triple("yesterday", LocalDate(2026, 10, 13), "Today"),
                Triple("today", LocalDate(2026, 10, 14), "Today"),
                Triple("tomorrow", LocalDate(2026, 10, 15), "Upcoming"),
                Triple("later this week", LocalDate(2026, 10, 16), "Upcoming"),
                Triple("end of next week", LocalDate(2026, 10, 25), "Upcoming"),
            )

        cases.forEachIndexed { index, (label, dueDate, tab) ->
            val definition = tabs.first { it.first == tab }.second
            val task = task("t$index", dueDate)

            assertEquals(
                1,
                sectionsContaining(definition, task).size,
                "a task $label must be in one section of the $tab tab, not in " +
                    sectionsContaining(definition, task),
            )
        }
    }

    @Test
    fun `the undated task is in the Inbox tab and in no other`() {
        val task = task("task-undated", null)

        assertEquals(
            listOf("Inbox"),
            tabsContaining(task),
            "an undated task belongs to the Inbox tab — the tab named for it",
        )
        assertEquals(
            listOf("No Date"),
            sectionsContaining(AgendaPresets.Inbox, task),
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

        assertEquals(
            emptyList(),
            tabsContaining(task),
            "a task dated beyond next week is in no agenda tab — the tabs cover a " +
                "horizon, and the answer for \"what is due later\" is the Plans " +
                "timeline and the Calendar",
        )
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
        val tabSections = tabs.flatMap { (_, definition) -> definition.sections.map { it.name } }.toSet()
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
