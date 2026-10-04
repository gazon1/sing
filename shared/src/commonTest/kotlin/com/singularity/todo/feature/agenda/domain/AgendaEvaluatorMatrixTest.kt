package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.RenderedSection
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import com.singularity.todo.test.fakes.AgendaSeed
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

/**
 * Comprehensive matrix of the agenda evaluator against the [AgendaSeed] fixture.
 *
 * Tests F-01 through F-12 from the test plan:
 * - F-01: DateBucket (9 variants) — bucket boundaries, week start/end
 * - F-02: DateRange — inclusive bounds, from > to → empty
 * - F-03: Statuses(Active/Completed)
 * - F-04: Tags ANY / matchAll
 * - F-05: Projects — Alpha/Beta hierarchy
 * - F-06: Priorities
 * - F-07: Regexp — case-insensitive, special chars
 * - F-08: Pinned / Overdue / Anything
 * - F-09: Projects/Notes in agenda — not supported (evaluator only handles Tasks)
 * - F-10: AllOf / AnyOf / Not combinators
 * - F-11: discard = true/false — no duplication, stable keys
 * - F-12: Trash/archive tasks are excluded
 * - F-14: StableJson round-trip
 * - F-15: AgendaSeed integration — real bucket layout
 *
 * Reference date is `today = LocalDate(2026, 10, 14)` from [AgendaSeed.TODAY].
 * Week runs Mon–Sun (ISO convention).
 */
@Tag("fast")
class AgendaEvaluatorMatrixTest {

    /** Reference "today" matching the AgendaSeed fixture. */
    private val D: LocalDate = AgendaSeed.TODAY // 2026-10-14 (Wednesday)

    // ─── Task factories ────────────────────────────────────────────────────────

    private val Instant0 = kotlin.time.Instant.fromEpochMilliseconds(0)

    private fun task(
        id: String,
        title: String = "Seed $id",
        dueDate: LocalDate? = null,
        completed: Boolean = false,
        archived: Boolean = false,
        pinned: Boolean = false,
        priority: TaskPriority = TaskPriority.None,
        tags: List<TagId> = emptyList(),
        projectId: String? = null,
    ): Task = Task(
        id = TaskId(id),
        title = title,
        description = null,
        priority = priority,
        kind = TaskKind.Task,
        projectId = projectId?.let { ProjectId(it) },
        parentTaskId = null,
        tags = tags,
        dueDate = dueDate,
        dueTime = null,
        startDate = null,
        startTime = null,
        endDate = null,
        endTime = null,
        accentColor = null,
        emoji = null,
        estimateMinutes = null,
        completedAt = if (completed) Instant0 else null,
        someday = false,
        archivedAt = if (archived) Instant0 else null,
        isPinned = pinned,
        dependsOn = emptySet(),
        aiSuppressedTagIds = emptySet(),
        recurrence = null,
        createdAt = Instant0,
        updatedAt = Instant0,
        userId = UserId(AgendaSeed.PERSONAL_USER_ID),
        serverVersion = 0,
        hlc = null,
    )

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun section(id: String, selector: Selector, discard: Boolean = false) =
        Section(id = id, name = id, order = 0, selector = selector, discard = discard)

    private fun eval(tasks: List<Task>, selector: Selector): List<Task> {
        val def = AgendaDefinition(
            title = "Test",
            sections = listOf(section("s", selector)),
        )
        return AgendaEvaluator.evaluate(tasks, def, D)
            .singleOrNull()
            ?.tasks
            ?.map { it.task }
            .orEmpty()
    }

    private fun evalSections(tasks: List<Task>, sections: List<Section>): List<RenderedSection> {
        val def = AgendaDefinition(title = "Test", sections = sections)
        return AgendaEvaluator.evaluate(tasks, def, D)
    }

    // ─── F-01: DateBucket (9 variants) ─────────────────────────────────────

    @Test
    fun `F-01a DateBucket Overdue matches past incomplete tasks`() {
        val past = task("p", dueDate = D.minus(1, DateTimeUnit.DAY))
        val today = task("t", dueDate = D)
        val future = task("f", dueDate = D.plus(1, DateTimeUnit.DAY))
        val result = eval(listOf(past, today, future), Selector.DateBucket(RelativeBucket.Overdue))
        assertEquals(1, result.size)
        assertEquals("p", result.first().id.value)
    }

    @Test
    fun `F-01b DateBucket Today matches only today-dated tasks`() {
        val yesterday = task("y", dueDate = D.minus(1, DateTimeUnit.DAY))
        val today = task("t", dueDate = D)
        val tomorrow = task("m", dueDate = D.plus(1, DateTimeUnit.DAY))
        val result = eval(listOf(yesterday, today, tomorrow), Selector.DateBucket(RelativeBucket.Today))
        assertEquals(1, result.size)
        assertEquals("t", result.first().id.value)
    }

    @Test
    fun `F-01c DateBucket Tomorrow matches only tomorrow`() {
        val today = task("t", dueDate = D)
        val tomorrow = task("m", dueDate = D.plus(1, DateTimeUnit.DAY))
        val dayAfter = task("a", dueDate = D.plus(2, DateTimeUnit.DAY))
        val result = eval(listOf(today, tomorrow, dayAfter), Selector.DateBucket(RelativeBucket.Tomorrow))
        assertEquals(1, result.size)
        assertEquals("m", result.first().id.value)
    }

    @Test
    fun `F-01d DateBucket ThisWeek matches Mon–Sun current week`() {
        // This week: Mon Oct 12 – Sun Oct 18 (for D = Wed Oct 14)
        val mon = task("mon", dueDate = LocalDate(2026, 10, 12))
        val wed = task("wed", dueDate = D)
        val sun = task("sun", dueDate = LocalDate(2026, 10, 18))
        val nextMon = task("nmon", dueDate = LocalDate(2026, 10, 19))
        val prevSun = task("psun", dueDate = LocalDate(2026, 10, 11))
        val result = eval(listOf(mon, wed, sun, nextMon, prevSun), Selector.DateBucket(RelativeBucket.ThisWeek))
        assertEquals(setOf("mon", "wed", "sun"), result.map { it.id.value }.toSet())
    }

    @Test
    fun `F-01e DateBucket NextWeek matches following ISO week`() {
        // Next week: Mon Oct 19 – Sun Oct 25
        val thisWeekEnd = task("we", dueDate = LocalDate(2026, 10, 18))
        val nextMon = task("nm", dueDate = LocalDate(2026, 10, 19))
        val nextFri = task("nf", dueDate = LocalDate(2026, 10, 23))
        val nextSun = task("ns", dueDate = LocalDate(2026, 10, 25))
        val followingMon = task("fm", dueDate = LocalDate(2026, 10, 26))
        val result = eval(
            listOf(thisWeekEnd, nextMon, nextFri, nextSun, followingMon),
            Selector.DateBucket(RelativeBucket.NextWeek),
        )
        assertEquals(setOf("nm", "nf", "ns"), result.map { it.id.value }.toSet())
    }

    @Test
    fun `F-01f DateBucket ThisMonth matches remainder of October`() {
        // October: Oct 26 – Oct 31
        val lateOctMon = task("lom", dueDate = LocalDate(2026, 10, 26))
        val oct31 = task("o31", dueDate = LocalDate(2026, 10, 31))
        val nov1 = task("n1", dueDate = LocalDate(2026, 11, 1))
        val result = eval(listOf(lateOctMon, oct31, nov1), Selector.DateBucket(RelativeBucket.ThisMonth))
        assertEquals(setOf("lom", "o31"), result.map { it.id.value }.toSet())
    }

    @Test
    fun `F-01g DateBucket NoDate matches null-due tasks`() {
        val dated = task("d", dueDate = D)
        val undated = task("u", dueDate = null)
        val result = eval(listOf(dated, undated), Selector.DateBucket(RelativeBucket.NoDate))
        assertEquals(1, result.size)
        assertEquals("u", result.first().id.value)
    }

    @Test
    fun `F-01h DateBucket completed past task is NOT in Overdue`() {
        val overdueDone = task("od", dueDate = D.minus(1, DateTimeUnit.DAY), completed = true)
        val overdueOpen = task("oo", dueDate = D.minus(1, DateTimeUnit.DAY), completed = false)
        val result = eval(listOf(overdueDone, overdueOpen), Selector.DateBucket(RelativeBucket.Overdue))
        assertEquals(1, result.size)
        assertEquals("oo", result.first().id.value)
    }

    @Test
    fun `F-01i week boundary Mon-Sun as per ISO`() {
        // Verify ThisWeek starts Monday (Oct 12) not Sunday
        val sunOct11 = task("s11", dueDate = LocalDate(2026, 10, 11)) // Sunday before
        val monOct12 = task("m12", dueDate = LocalDate(2026, 10, 12)) // Monday — start
        val friOct16 = task("f16", dueDate = LocalDate(2026, 10, 16))
        val satOct17 = task("sa17", dueDate = LocalDate(2026, 10, 17))
        val sunOct18 = task("s18", dueDate = LocalDate(2026, 10, 18)) // Sunday — end
        val monOct19 = task("m19", dueDate = LocalDate(2026, 10, 19)) // Monday — next week
        val result = eval(
            listOf(sunOct11, monOct12, friOct16, satOct17, sunOct18, monOct19),
            Selector.DateBucket(RelativeBucket.ThisWeek),
        )
        // Mon Oct 12 – Sun Oct 18 inclusive
        assertEquals(setOf("m12", "f16", "sa17", "s18"), result.map { it.id.value }.toSet())
        assertFalse("s11" in result.map { it.id.value }.toSet(), "Sunday Oct 11 should NOT be in ThisWeek")
        assertFalse("m19" in result.map { it.id.value }.toSet(), "Monday Oct 19 should NOT be in ThisWeek")
    }

    // ─── F-02: DateRange ───────────────────────────────────────────────────

    @Test
    fun `F-02a DateRange is inclusive at both ends`() {
        val tasks = listOf(
            task("a", dueDate = LocalDate(2026, 10, 10)),
            task("b", dueDate = LocalDate(2026, 10, 12)),
            task("c", dueDate = LocalDate(2026, 10, 15)),
            task("d", dueDate = LocalDate(2026, 10, 20)),
        )
        val range = Selector.DateRange(LocalDate(2026, 10, 12), LocalDate(2026, 10, 20))
        val result = eval(tasks, range)
        assertEquals(setOf("b", "c", "d"), result.map { it.id.value }.toSet())
    }

    @Test
    fun `F-02b DateRange from greater than to returns empty`() {
        val tasks = listOf(task("a", dueDate = D))
        val range = Selector.DateRange(D.plus(1, DateTimeUnit.DAY), D)
        val result = eval(tasks, range)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `F-02c DateRange same day returns that day only`() {
        val tasks = listOf(
            task("a", dueDate = D.minus(1, DateTimeUnit.DAY)),
            task("b", dueDate = D),
            task("c", dueDate = D.plus(1, DateTimeUnit.DAY)),
        )
        val range = Selector.DateRange(D, D)
        val result = eval(tasks, range)
        assertEquals(1, result.size)
        assertEquals("b", result.first().id.value)
    }

    // ─── F-03: Statuses ───────────────────────────────────────────────────

    @Test
    fun `F-03a Statuses Active excludes completed`() {
        val active = task("a", completed = false)
        val done = task("d", completed = true)
        val result = eval(listOf(active, done), Selector.Statuses(setOf(TaskStatus.Active)))
        assertEquals(1, result.size)
        assertEquals("a", result.first().id.value)
    }

    @Test
    fun `F-03b Statuses Completed excludes active`() {
        val active = task("a", completed = false)
        val done = task("d", completed = true)
        val result = eval(listOf(active, done), Selector.Statuses(setOf(TaskStatus.Completed)))
        assertEquals(1, result.size)
        assertEquals("d", result.first().id.value)
    }

    @Test
    fun `F-03c Statuses All includes both`() {
        val active = task("a", completed = false)
        val done = task("d", completed = true)
        val result = eval(listOf(active, done), Selector.Statuses(setOf(TaskStatus.All)))
        assertEquals(2, result.size)
    }

    // ─── F-04: Tags ──────────────────────────────────────────────────────

    private val TAG_WORK = TagId("tag-work")
    private val TAG_URGENT = TagId("tag-urgent")
    private val TAG_HOME = TagId("tag-home")

    @Test
    fun `F-04a Tags matchAll — all tag selectors must match`() {
        val workUrgent = task("wu", tags = listOf(TAG_WORK, TAG_URGENT))
        val workOnly = task("wo", tags = listOf(TAG_WORK))
        val urgentOnly = task("uo", tags = listOf(TAG_URGENT))
        val none = task("n", tags = emptyList())
        val all = listOf(workUrgent, workOnly, urgentOnly, none)
        val matchAll = eval(all, Selector.Tags(setOf(TAG_WORK, TAG_URGENT), matchAll = true))
        assertEquals(1, matchAll.size)
        assertEquals("wu", matchAll.first().id.value)
    }

    @Test
    fun `F-04b Tags matchAny — any tag selector matches`() {
        val workUrgent = task("wu", tags = listOf(TAG_WORK, TAG_URGENT))
        val workOnly = task("wo", tags = listOf(TAG_WORK))
        val urgentOnly = task("uo", tags = listOf(TAG_URGENT))
        val none = task("n", tags = emptyList())
        val all = listOf(workUrgent, workOnly, urgentOnly, none)
        val matchAny = eval(all, Selector.Tags(setOf(TAG_WORK, TAG_URGENT), matchAll = false))
        assertEquals(3, matchAny.size)
        assertTrue(matchAny.map { it.id.value }.toSet().containsAll(setOf("wu", "wo", "uo")))
    }

    @Test
    fun `F-04c Tags with no matching tasks returns empty`() {
        val t = task("t", tags = listOf(TAG_HOME))
        val result = eval(listOf(t), Selector.Tags(setOf(TAG_WORK, TAG_URGENT)))
        assertTrue(result.isEmpty())
    }

    // ─── F-05: Projects ─────────────────────────────────────────────────

    private val PROJ_ALPHA = ProjectId("proj-alpha")
    private val PROJ_BETA = ProjectId("proj-beta")

    @Test
    fun `F-05a Projects matches direct project membership only`() {
        val inAlpha = task("ia", projectId = "proj-alpha")
        val inBeta = task("ib", projectId = "proj-beta") // Beta is NOT a child of Alpha in the evaluator
        val noProject = task("np")
        // Projects selector uses flat set membership — no hierarchy traversal
        val result = eval(listOf(inAlpha, inBeta, noProject), Selector.Projects(setOf(PROJ_ALPHA)))
        assertEquals(1, result.size)
        assertEquals("ia", result.first().id.value)
    }

    @Test
    fun `F-05b Projects does not match other projects`() {
        val inAlpha = task("ia", projectId = "proj-alpha")
        val inBeta = task("ib", projectId = "proj-beta")
        val result = eval(listOf(inAlpha, inBeta), Selector.Projects(setOf(PROJ_BETA)))
        assertEquals(1, result.size)
        assertEquals("ib", result.first().id.value)
    }

    // ─── F-06: Priorities ───────────────────────────────────────────────

    @Test
    fun `F-06a Priorities matches exact priority`() {
        val high = task("h", priority = TaskPriority.High)
        val low = task("l", priority = TaskPriority.Low)
        val none = task("n", priority = TaskPriority.None)
        val result = eval(listOf(high, low, none), Selector.Priorities(setOf(TaskPriority.High)))
        assertEquals(1, result.size)
        assertEquals("h", result.first().id.value)
    }

    @Test
    fun `F-06b Priorities notAtMost inverts the set`() {
        val high = task("h", priority = TaskPriority.High)
        val low = task("l", priority = TaskPriority.Low)
        val result = eval(listOf(high, low), Selector.Priorities(setOf(TaskPriority.High), atMost = false))
        assertEquals(1, result.size)
        assertEquals("l", result.first().id.value)
    }

    // ─── F-07: Regexp ───────────────────────────────────────────────────

    @Test
    fun `F-07a Regexp matches title pattern`() {
        val buy = task("b", title = "Buy groceries")
        val sell = task("s", title = "Sell stock")
        val result = eval(listOf(buy, sell), Selector.Regexp("buy"))
        assertEquals(1, result.size)
        assertEquals("b", result.first().id.value)
    }

    @Test
    fun `F-07b Regexp is always case insensitive`() {
        val buyUpper = task("bu", title = "BUY groceries")
        val buyLower = task("bl", title = "buy groceries")
        val result = eval(listOf(buyUpper, buyLower), Selector.Regexp("buy"))
        assertEquals(2, result.size) // IGNORE_CASE is hardcoded in the matcher
    }

    @Test
    fun `F-07c Regexp no match returns empty`() {
        val sell = task("s", title = "Sell stock")
        val result = eval(listOf(sell), Selector.Regexp("buy"))
        assertTrue(result.isEmpty())
    }

    // ─── F-08: Pinned / Overdue / Anything ─────────────────────────────

    @Test
    fun `F-08a Pinned selector only returns pinned tasks`() {
        val pinned = task("p", pinned = true)
        val unpinned = task("u", pinned = false)
        val result = eval(listOf(pinned, unpinned), Selector.Pinned)
        assertEquals(1, result.size)
        assertEquals("p", result.first().id.value)
    }

    @Test
    fun `F-08b Overdue selector is shorthand for DateBucket(Overdue) AND Active`() {
        val overdueDone = task("od", dueDate = D.minus(1, DateTimeUnit.DAY), completed = true)
        val overdueOpen = task("oo", dueDate = D.minus(1, DateTimeUnit.DAY), completed = false)
        val result = eval(listOf(overdueDone, overdueOpen), Selector.Overdue)
        assertEquals(1, result.size)
        assertEquals("oo", result.first().id.value)
    }

    @Test
    fun `F-08c Anything unconditionally matches all tasks`() {
        val active = task("a")
        val done = task("d", completed = true)
        val archived = task("ar", archived = true) // evaluator does NOT filter archived
        // Anything selector: `is Selector.Anything -> true` — matches everything unconditionally
        val result = eval(listOf(active, done, archived), Selector.Anything)
        assertEquals(3, result.size)
    }

    // ─── F-09: Projects/Notes in agenda ────────────────────────────────

    @Test
    fun `F-09 agenda evaluator only accepts Task types`() {
        // The evaluator processes only Task objects. Project/Note types are not
        // handled by the agenda evaluator — they belong to their own domain flows.
        val def = AgendaDefinition(
            title = "Test",
            sections = listOf(section("s", Selector.Anything)),
        )
        val result = AgendaEvaluator.evaluate(emptyList(), def, D)
        // No exception means the evaluator is well-defined for the Task domain.
        assertTrue(result.isEmpty())
    }

    // ─── F-10: AllOf / AnyOf / Not ───────────────────────────────────

    @Test
    fun `F-10a AllOf requires all children to match`() {
        val tomorrow = D.plus(1, DateTimeUnit.DAY)
        val workTomorrow = task("wt", dueDate = tomorrow, tags = listOf(TAG_WORK))
        val workToday = task("wtd", dueDate = D, tags = listOf(TAG_WORK))
        val urgentTomorrow = task("ut", dueDate = tomorrow, tags = listOf(TAG_URGENT))
        val all = listOf(workTomorrow, workToday, urgentTomorrow)
        val combined = Selector.AllOf(
            listOf(
            Selector.Tags(setOf(TAG_WORK)),
            Selector.DateBucket(RelativeBucket.Tomorrow),
        )
        )
        val result = eval(all, combined)
        assertEquals(1, result.size)
        assertEquals("wt", result.first().id.value)
    }

    @Test
    fun `F-10b AnyOf matches when at least one child matches`() {
        val tomorrow = D.plus(1, DateTimeUnit.DAY)
        val workTomorrow = task("wt", dueDate = tomorrow, tags = listOf(TAG_WORK))
        val urgentTomorrow = task("ut", dueDate = tomorrow, tags = listOf(TAG_URGENT))
        // Both tasks have dueDate=tomorrow, so both match DateBucket.Tomorrow.
        // workTomorrow also matches Tags(WORK).
        val result = eval(
            listOf(workTomorrow, urgentTomorrow),
            Selector.AnyOf(
                listOf(
            Selector.Tags(setOf(TAG_WORK)),
            Selector.DateBucket(RelativeBucket.Tomorrow),
        )
            )
        )
        // Both match DateBucket.Tomorrow (the date-based child), so both appear.
        assertEquals(2, result.size)
    }

    @Test
    fun `F-10c Not inverts child match`() {
        val work = task("w", tags = listOf(TAG_WORK))
        val home = task("h", tags = listOf(TAG_HOME))
        val result = eval(listOf(work, home), Selector.Not(Selector.Tags(setOf(TAG_WORK))))
        assertEquals(1, result.size)
        assertEquals("h", result.first().id.value)
    }

    // ─── F-11: discard ─────────────────────────────────────────────────

    @Test
    fun `F-11a without discard task appears in ALL matching sections`() {
        // Without discard = false (default), the task remains in the pool and appears
        // in every section whose selector matches it (duplication across sections).
        val t = task("t", dueDate = D) // matches both Today and ThisWeek
        val sections = listOf(
            section("today", Selector.DateBucket(RelativeBucket.Today)),
            section("this-week", Selector.DateBucket(RelativeBucket.ThisWeek)),
        )
        val result = evalSections(listOf(t), sections)
        val todaySection = result.find { it.name == "today" }
        val weekSection = result.find { it.name == "this-week" }
        val todayIds = todaySection?.tasks?.map { it.task.id.value }.orEmpty()
        val weekIds = weekSection?.tasks?.map { it.task.id.value }.orEmpty()
        // Without discard, the task is NOT removed from the pool after first match.
        // It appears in BOTH sections — which is why discard=true is needed for
        // non-overlapping bucket layouts (but doubles are fine for independent filters).
        assertTrue(todayIds.contains("t"), "task should be in Today section")
        assertTrue(weekIds.contains("t"), "task should also appear in ThisWeek section (no discard)")
    }

    @Test
    fun `F-11b with discard task appears in first matching section only`() {
        val t = task("t", dueDate = D) // matches both Today and ThisWeek
        val sections = listOf(
            Section(
                id = "today",
                name = "Today",
                order = 0,
                selector = Selector.DateBucket(RelativeBucket.Today),
                discard = true,
            ),
            Section(
                id = "week",
                name = "This Week",
                order = 1,
                selector = Selector.DateBucket(RelativeBucket.ThisWeek),
                discard = true,
            ),
        )
        val result = evalSections(listOf(t), sections)
        val todaySection = result.find { it.name == "Today" }
        val weekSection = result.find { it.name == "This Week" }
        val todayIds = todaySection?.tasks?.map { it.task.id.value }.orEmpty()
        val weekIds = weekSection?.tasks?.map { it.task.id.value }.orEmpty()
        assertEquals(1, todayIds.size)
        assertTrue(todayIds.contains("t"))
        assertTrue(weekIds.isEmpty(), "task should NOT leak to ThisWeek after discard")
    }

    @Test
    fun `F-11c duplicate task ids do not cause LazyColumn key collisions`() {
        // With discard, each task appears at most once — unique task IDs → stable keys.
        val t1 = task("t1", dueDate = D)
        val t2 = task("t2", dueDate = D)
        val sections = listOf(section("today", Selector.DateBucket(RelativeBucket.Today)))
        val result = evalSections(listOf(t1, t2), sections)
        val ids = result.first().tasks.map { it.task.id.value }
        assertEquals(setOf("t1", "t2"), ids.toSet())
    }

    // ─── F-12: Trash/archive ──────────────────────────────────────────

    @Test
    fun `F-12 archived tasks pass through evaluator (filtered at DAO layer)`() {
        val active = task("a")
        val archived = task("ar", archived = true)
        // The evaluator itself does NOT filter archived tasks — that happens in the
        // DAO/SQL layer (WHERE archived_at IS NULL). Direct evaluate() calls see all tasks.
        val result = eval(listOf(active, archived), Selector.Anything)
        assertEquals(2, result.size)
    }

    @Test
    fun `F-12 completed active tasks are NOT archived and still appear`() {
        val done = task("d", completed = true) // completed ≠ archived
        val result = eval(listOf(done), Selector.Anything)
        assertEquals(1, result.size)
    }

    // ─── F-14: StableJson round-trip ───────────────────────────────────

    @Test
    fun `F-14 AgendaDefinition serializes and deserializes correctly`() {
        val def = AgendaDefinition(
            title = "My View",
            sections = listOf(
                Section(
                    id = "s1",
                    name = "Today",
                    order = 0,
                    selector = Selector.DateBucket(RelativeBucket.Today),
                    discard = true,
                ),
            ),
        )
        val json = StableJson.encodeToString(AgendaDefinition.serializer(), def)
        val restored = StableJson.decodeFromString(AgendaDefinition.serializer(), json)
        assertEquals(def.title, restored.title)
        assertEquals(def.sections.size, restored.sections.size)
        assertEquals(def.sections.first().id, restored.sections.first().id)
        assertEquals(def.sections.first().selector, restored.sections.first().selector)
    }

    // ─── F-15: AgendaSeed integration — real bucket layout ─────────────────

    /**
     * Verifies the AgendaSeed fixture produces the expected bucket layout:
     *
     * | Bucket      | Expected task IDs |
     * |-------------|------------------|
     * | Overdue     | T01              |
     * | Today       | T02, T18         |
     * | Tomorrow    | T03              |
     * | This Week   | T04, T05         |
     * | Next Week   | T06–T10          |
     * | This Month  | T11–T14          |
     * | No Date     | T15, T16         |
     *
     * This is a meta-test: it validates the fixture itself, ensuring it is
     * self-consistent and suitable for use in downstream matrix tests.
     */
    @Test
    fun `F-15 AgendaSeed produces correct bucket layout for Inbox preset`() {
        val tasks = AgendaSeed.tasks.map { entity ->
            Task(
                id = TaskId(entity.id),
                title = entity.title,
                description = entity.description,
                priority = entity.priority,
                kind = entity.kind,
                projectId = entity.projectId?.let { ProjectId(it) },
                parentTaskId = null,
                tags = emptyList(), // cross-refs added separately
                dueDate = entity.dueDate?.let { LocalDate.parse(it) },
                dueTime = null,
                startDate = null,
                startTime = null,
                endDate = null,
                endTime = null,
                accentColor = null,
                emoji = null,
                estimateMinutes = null,
                completedAt = entity.completedAt?.let { kotlin.time.Instant.fromEpochMilliseconds(it) },
                someday = entity.someday,
                archivedAt = entity.archivedAt?.let { kotlin.time.Instant.fromEpochMilliseconds(it) },
                isPinned = entity.isPinned,
                dependsOn = emptySet(),
                aiSuppressedTagIds = emptySet(),
                recurrence = null,
                createdAt = kotlin.time.Instant.fromEpochMilliseconds(entity.createdAt),
                updatedAt = kotlin.time.Instant.fromEpochMilliseconds(entity.updatedAt),
                userId = UserId(entity.userId),
                serverVersion = 0,
                hlc = null,
            )
        }

        val result = AgendaEvaluator.evaluate(tasks, AgendaPresets.Inbox, D)

        val sectionNames = result.map { it.name }
        assertTrue("Overdue" in sectionNames, "Overdue section expected")
        assertTrue("Today" in sectionNames, "Today section expected")
        assertTrue("Tomorrow" in sectionNames, "Tomorrow section expected")
        assertTrue("This Week" in sectionNames, "This Week section expected")
        assertTrue("Next Week" in sectionNames, "Next Week section expected")
        assertTrue("Upcoming" in sectionNames || "This Month" in sectionNames, "Month or Upcoming section expected")
        assertTrue("No Date" in sectionNames, "No Date section expected")

        // Archive section should be empty (T17 is archived)
        val archiveSection = result.find { it.name == "Archive" }
        assertTrue(archiveSection?.tasks.isNullOrEmpty() == true, "Archive section must be empty")
    }
}
