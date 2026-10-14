package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.model.Selector
import com.singularity.todo.feature.agenda.domain.selector.SelectorContext
import com.singularity.todo.feature.agenda.domain.selector.matches
import kotlin.time.Duration.Companion.days
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("fast")
class AgendaEvaluatorTest {

    // Fixed date so tests are deterministic regardless of the machine's system date.
    private val today: LocalDate = LocalDate(2026, Month.SEPTEMBER, 16)

    // SelectorContext for all Selector.matches calls in this class.
    // blockedIds is empty since no tests exercise the blocked path here.
    private val context = SelectorContext(
        today = today,
        now = kotlin.time.Instant.fromEpochMilliseconds(0),
        blockedIds = emptySet(),
    )

    /**
     * Context for [Selector.Stagnant] tests where `now - updatedAt >= 21.days`.
     * With [kotlin.time.Instant] epoch 0, set `updatedAt = -22.days` to get a 22-day gap.
     */
    private val staleContext = SelectorContext(
        today = today,
        now = kotlin.time.Instant.fromEpochMilliseconds(0),
        blockedIds = emptySet(),
    )

    /**
     * Context for [Selector.Blocked] tests with a non-empty [SelectorContext.blockedIds].
     */
    private fun blockedContext(vararg blocked: String) = SelectorContext(
        today = today,
        now = kotlin.time.Instant.fromEpochMilliseconds(0),
        blockedIds = blocked.map { com.singularity.todo.feature.tasks.domain.model.TaskId(it) }.toSet(),
    )

    private fun makeTask(
        id: String,
        title: String,
        dueDate: LocalDate? = null,
        priority: TaskPriority = TaskPriority.None,
        isCompleted: Boolean = false,
        isPinned: Boolean = false,
        tags: List<TagId> = emptyList(),
        projectId: ProjectId? = null,
        updatedAt: kotlin.time.Instant = kotlin.time.Instant.fromEpochMilliseconds(0),
    ): Task = Task(
        id = com.singularity.todo.feature.tasks.domain.model.TaskId(id),
        title = title,
        dueDate = dueDate,
        priority = priority,
        isPinned = isPinned,
        tags = tags,
        projectId = projectId,
        kind = TaskKind.Task,
        completedAt = if (isCompleted) kotlin.time.Instant.fromEpochMilliseconds(0) else null,
        createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        updatedAt = updatedAt,
        userId = com.singularity.todo.core.ids.UserId("test-user"),
    )

    // ─── Selector: DateBucket ──────────────────────────────────────────────────

    @Test
    fun `DateBucket Today matches task due today`() {
        val task = makeTask("1", "Buy milk", dueDate = today)
        assertTrue(AgendaEvaluator.matches(task, Selector.DateBucket(RelativeBucket.Today), context))
    }

    @Test
    fun `DateBucket Today does not match task due yesterday`() {
        val task = makeTask("1", "Buy milk", dueDate = today.minus(1, DateTimeUnit.DAY))
        assertFalse(AgendaEvaluator.matches(task, Selector.DateBucket(RelativeBucket.Today), context))
    }

    @Test
    fun `DateBucket ThisWeek matches task within current week`() {
        val task = makeTask("1", "Buy milk", dueDate = today.plus(2, DateTimeUnit.DAY))
        assertTrue(AgendaEvaluator.matches(task, Selector.DateBucket(RelativeBucket.ThisWeek), context))
    }

    @Test
    fun `DateBucket Overdue matches past incomplete task`() {
        val task = makeTask("1", "Buy milk", dueDate = today.minus(1, DateTimeUnit.DAY), isCompleted = false)
        assertTrue(AgendaEvaluator.matches(task, Selector.DateBucket(RelativeBucket.Overdue), context))
    }

    @Test
    fun `DateBucket Overdue does not match completed overdue task`() {
        val task = makeTask("1", "Buy milk", dueDate = today.minus(1, DateTimeUnit.DAY), isCompleted = true)
        assertFalse(AgendaEvaluator.matches(task, Selector.DateBucket(RelativeBucket.Overdue), context))
    }

    @Test
    fun `DateBucket NoDate matches task without due date`() {
        val task = makeTask("1", "Buy milk", dueDate = null)
        assertTrue(AgendaEvaluator.matches(task, Selector.DateBucket(RelativeBucket.NoDate), context))
    }

    @Test
    fun `DateBucket NoDate does not match task with due date`() {
        val task = makeTask("1", "Buy milk", dueDate = today)
        assertFalse(AgendaEvaluator.matches(task, Selector.DateBucket(RelativeBucket.NoDate), context))
    }

    // ─── Selector: Statuses ────────────────────────────────────────────────────

    @Test
    fun `Statuses Active matches incomplete task`() {
        val task = makeTask("1", "Active task", isCompleted = false)
        assertTrue(AgendaEvaluator.matches(task, Selector.Statuses(setOf(TaskStatus.Active)), context))
    }

    @Test
    fun `Statuses Active does not match completed task`() {
        val task = makeTask("1", "Done task", isCompleted = true)
        assertFalse(AgendaEvaluator.matches(task, Selector.Statuses(setOf(TaskStatus.Active)), context))
    }

    @Test
    fun `Statuses Completed matches completed task`() {
        val task = makeTask("1", "Done task", isCompleted = true)
        assertTrue(AgendaEvaluator.matches(task, Selector.Statuses(setOf(TaskStatus.Completed)), context))
    }

    @Test
    fun `Statuses All matches any non-trashed task`() {
        val active = makeTask("1", "Active", isCompleted = false)
        val done = makeTask("2", "Done", isCompleted = true)
        assertTrue(AgendaEvaluator.matches(active, Selector.Statuses(setOf(TaskStatus.All)), context))
        assertTrue(AgendaEvaluator.matches(done, Selector.Statuses(setOf(TaskStatus.All)), context))
    }

    @Test
    fun `Statuses ActivePlusCompleted equals All`() {
        val active = makeTask("1", "Active", isCompleted = false)
        val done = makeTask("2", "Done", isCompleted = true)
        val statuses = setOf(TaskStatus.Active, TaskStatus.Completed)
        assertTrue(AgendaEvaluator.matches(active, Selector.Statuses(statuses), context))
        assertTrue(AgendaEvaluator.matches(done, Selector.Statuses(statuses), context))
    }

    // ─── Selector: Priorities ─────────────────────────────────────────────────

    @Test
    fun `Priorities atMost matches task in set`() {
        val task = makeTask("1", "High priority", priority = TaskPriority.High)
        val selector = Selector.Priorities(setOf(TaskPriority.High, TaskPriority.Urgent))
        assertTrue(selector.matches(task, context))
    }

    @Test
    fun `Priorities atMost does not match task outside set`() {
        val task = makeTask("1", "Low priority", priority = TaskPriority.Low)
        val selector = Selector.Priorities(setOf(TaskPriority.High, TaskPriority.Urgent))
        assertFalse(selector.matches(task, context))
    }

    @Test
    fun `Priorities notAtMost matches task outside set`() {
        val task = makeTask("1", "Low priority", priority = TaskPriority.Low)
        val selector = Selector.Priorities(setOf(TaskPriority.High, TaskPriority.Urgent), atMost = false)
        assertTrue(selector.matches(task, context))
    }

    // ─── Selector: Tags ───────────────────────────────────────────────────────

    @Test
    fun `Tags matches task with that tag`() {
        val tag = TagId.fromString("tag-1")
        val task = makeTask("1", "Tagged task", tags = listOf(tag))
        assertTrue(AgendaEvaluator.matches(task, Selector.Tags(setOf(tag)), context))
    }

    @Test
    fun `Tags does not match task without that tag`() {
        val tag = TagId.fromString("tag-1")
        val task = makeTask("1", "Untagged task", tags = emptyList())
        assertFalse(AgendaEvaluator.matches(task, Selector.Tags(setOf(tag)), context))
    }

    // ─── Selector: Projects ──────────────────────────────────────────────────

    @Test
    fun `Projects matches task in set`() {
        val projectId = ProjectId.fromString("proj-1")
        val task = makeTask("1", "Project task", projectId = projectId)
        assertTrue(AgendaEvaluator.matches(task, Selector.Projects(setOf(projectId)), context))
    }

    @Test
    fun `Projects does not match task in different project`() {
        val projectId = ProjectId.fromString("proj-1")
        val task = makeTask("1", "Other project task", projectId = ProjectId.fromString("proj-2"))
        assertFalse(AgendaEvaluator.matches(task, Selector.Projects(setOf(projectId)), context))
    }

    // ─── Selector: Pinned / Completed ─────────────────────────────────────────

    @Test
    fun `Pinned matches pinned task`() {
        val task = makeTask("1", "Pinned task", isPinned = true)
        assertTrue(AgendaEvaluator.matches(task, Selector.Pinned, context))
    }

    @Test
    fun `Pinned does not match unpinned task`() {
        val task = makeTask("1", "Unpinned task", isPinned = false)
        assertFalse(AgendaEvaluator.matches(task, Selector.Pinned, context))
    }

    @Test
    fun `Completed matches completed task`() {
        val task = makeTask("1", "Done task", isCompleted = true)
        assertTrue(AgendaEvaluator.matches(task, Selector.Completed, context))
    }

    // ─── Selector: Overdue ───────────────────────────────────────────────────

    @Test
    fun `Overdue matches past incomplete task`() {
        val task = makeTask("1", "Late!", dueDate = today.minus(1, DateTimeUnit.DAY), isCompleted = false)
        assertTrue(AgendaEvaluator.matches(task, Selector.Overdue, context))
    }

    @Test
    fun `Overdue does not match future task`() {
        val task = makeTask("1", "Future task", dueDate = today.plus(1, DateTimeUnit.DAY))
        assertFalse(AgendaEvaluator.matches(task, Selector.Overdue, context))
    }

    // ─── Selector: Regexp ─────────────────────────────────────────────────────

    @Test
    fun `Regexp matches title pattern`() {
        val task = makeTask("1", "Buy groceries at the store")
        assertTrue(AgendaEvaluator.matches(task, Selector.Regexp("buy.*store"), context))
    }

    @Test
    fun `Regexp is case insensitive`() {
        val task = makeTask("1", "Buy groceries")
        assertTrue(AgendaEvaluator.matches(task, Selector.Regexp("BUY"), context))
    }

    @Test
    fun `Regexp does not match non-matching title`() {
        val task = makeTask("1", "Sell stuff")
        assertFalse(AgendaEvaluator.matches(task, Selector.Regexp("buy"), context))
    }

    // ─── Selector: AllOf / AnyOf / Not ────────────────────────────────────────

    @Test
    fun `AllOf matches when all children match`() {
        val task = makeTask("1", "Active pinned", isCompleted = false, isPinned = true)
        val selector = Selector.AllOf(listOf(Selector.Pinned, Selector.Statuses(setOf(TaskStatus.Active))))
        assertTrue(selector.matches(task, context))
    }

    @Test
    fun `AllOf does not match when one child fails`() {
        val task = makeTask("1", "Active unpinned", isCompleted = false, isPinned = false)
        val selector = Selector.AllOf(listOf(Selector.Pinned, Selector.Statuses(setOf(TaskStatus.Active))))
        assertFalse(selector.matches(task, context))
    }

    @Test
    fun `AnyOf matches when at least one child matches`() {
        val task = makeTask("1", "Active unpinned", isCompleted = false, isPinned = false)
        val selector = Selector.AnyOf(listOf(Selector.Pinned, Selector.Statuses(setOf(TaskStatus.Active))))
        assertTrue(selector.matches(task, context))
    }

    @Test
    fun `AnyOf does not match when all children fail`() {
        val task = makeTask("1", "Completed unpinned", isCompleted = true, isPinned = false)
        val selector = Selector.AnyOf(listOf(Selector.Pinned, Selector.Statuses(setOf(TaskStatus.Active))))
        assertFalse(selector.matches(task, context))
    }

    @Test
    fun `Not inverts child match`() {
        val task = makeTask("1", "Pinned task", isPinned = true)
        assertFalse(AgendaEvaluator.matches(task, Selector.Not(Selector.Pinned), context))
        assertTrue(AgendaEvaluator.matches(task, Selector.Not(Selector.Completed), context))
    }

    // ─── Selector: Anything ───────────────────────────────────────────────────

    @Test
    fun `Anything matches any task`() {
        val task = makeTask("1", "Any task")
        assertTrue(AgendaEvaluator.matches(task, Selector.Anything, context))
    }

    // ─── Selector: Blocked ─────────────────────────────────────────────────────

    @Test
    fun `Blocked matches task in blockedIds`() {
        val task = makeTask("blocked-1", "Blocked task")
        val ctx = blockedContext("blocked-1", "blocked-2")
        assertTrue(Selector.Blocked.matches(task, ctx))
    }

    @Test
    fun `Blocked does not match task outside blockedIds`() {
        val task = makeTask("not-blocked", "Free task")
        val ctx = blockedContext("blocked-1", "blocked-2")
        assertFalse(Selector.Blocked.matches(task, ctx))
    }

    @Test
    fun `Blocked matches single-element blockedIds`() {
        val task = makeTask("only", "Only blocked")
        val ctx = blockedContext("only")
        assertTrue(Selector.Blocked.matches(task, ctx))
    }

    // ─── Selector: Stagnant ───────────────────────────────────────────────────

    @Test
    fun `Stagnant matches stale undated incomplete task`() {
        // updatedAt = -22 days; context.now = 0; gap = 22 days >= 21.days
        val staleUpdatedAt = kotlin.time.Instant.fromEpochMilliseconds(-22L * 24 * 60 * 60 * 1000)
        val task = makeTask("1", "Stale task", updatedAt = staleUpdatedAt)
        assertTrue(Selector.Stagnant(21.days).matches(task, staleContext))
    }

    @Test
    fun `Stagnant does not match recent task`() {
        // updatedAt = context.now; gap = 0 < 21.days
        val task = makeTask("1", "Recent task")
        assertFalse(Selector.Stagnant(21.days).matches(task, staleContext))
    }

    @Test
    fun `Stagnant excludes tasks with dueDate`() {
        val staleUpdatedAt = kotlin.time.Instant.fromEpochMilliseconds(-22L * 24 * 60 * 60 * 1000)
        val task = makeTask("1", "Dated stale task", dueDate = today, updatedAt = staleUpdatedAt)
        assertFalse(Selector.Stagnant(21.days).matches(task, staleContext))
    }

    @Test
    fun `Stagnant excludes completed tasks`() {
        val staleUpdatedAt = kotlin.time.Instant.fromEpochMilliseconds(-22L * 24 * 60 * 60 * 1000)
        val task = makeTask("1", "Done stale task", isCompleted = true, updatedAt = staleUpdatedAt)
        assertFalse(Selector.Stagnant(21.days).matches(task, staleContext))
    }

    @Test
    fun `Stagnant excludes blocked tasks — BLOCKED has precedence`() {
        // Task is stale AND is in blockedIds → should NOT match Stagnant
        val staleUpdatedAt = kotlin.time.Instant.fromEpochMilliseconds(-22L * 24 * 60 * 60 * 1000)
        val task = makeTask("blocked-stale", "Blocked stale task", updatedAt = staleUpdatedAt)
        val ctx = blockedContext("blocked-stale")
        assertFalse(Selector.Stagnant(21.days).matches(task, ctx))
        // But it DOES match Blocked
        assertTrue(Selector.Blocked.matches(task, ctx))
    }

    @Test
    fun `Stagnant threshold respects Duration parameter`() {
        val staleUpdatedAt = kotlin.time.Instant.fromEpochMilliseconds(-22L * 24 * 60 * 60 * 1000)
        val task = makeTask("1", "Task at 22 days", updatedAt = staleUpdatedAt)
        // 22 days >= 22 days threshold → matches
        assertTrue(Selector.Stagnant(22.days).matches(task, staleContext))
        // 22 days < 23 days threshold → does not match
        assertFalse(Selector.Stagnant(23.days).matches(task, staleContext))
    }

    // ─── AgendaEvaluator.evaluate ─────────────────────────────────────────────

    @Test
    fun `evaluate produces sections in order`() {
        val definition = AgendaDefinition(
            title = "Test",
            sections = listOf(
                Section(
                    id = "second",
                    name = "Second",
                    order = 1,
                    selector = Selector.DateBucket(RelativeBucket.Tomorrow),
                ),
                Section(id = "first", name = "First", order = 0, selector = Selector.DateBucket(RelativeBucket.Today)),
            ),
        )
        val tasks = listOf(
            makeTask("1", "Today task", dueDate = today),
            makeTask("2", "Tomorrow task", dueDate = today.plus(1, DateTimeUnit.DAY)),
        )
        val result = AgendaEvaluator.evaluate(tasks, definition, today, context.now)
        assertEquals(2, result.size)
        assertEquals("First", result[0].name)
        assertEquals("Second", result[1].name)
    }

    @Test
    fun `evaluate applies discard semantics`() {
        val definition = AgendaDefinition(
            title = "Test",
            sections = listOf(
                Section(id = "all", name = "All", order = 0, selector = Selector.Anything, discard = true), // discard removes matched tasks
                Section(
                    id = "only-overdue",
                    name = "Only Overdue",
                    order = 1,
                    selector = Selector.DateBucket(RelativeBucket.Overdue),
                ),
            ),
        )
        val tasks = listOf(
            makeTask("1", "Overdue task", dueDate = today.minus(1, DateTimeUnit.DAY)),
            makeTask("2", "Today task", dueDate = today),
        )
        val result = AgendaEvaluator.evaluate(tasks, definition, today, context.now)
        // "All" section captures both tasks; discard=true removes them from remaining.
        // "Only Overdue" section has 0 tasks (remaining is empty) and is dropped by mapNotNull.
        assertEquals(1, result.size)
        assertEquals("All", result[0].name)
        assertEquals(2, result[0].tasks.size)
    }

    @Test
    fun `evaluate computes overdue badge`() {
        val definition = AgendaDefinition(
            title = "Test",
            sections = listOf(
                Section(
                    id = "overdue",
                    name = "Overdue",
                    order = 0,
                    selector = Selector.DateBucket(RelativeBucket.Overdue),
                ),
            ),
        )
        val task = makeTask("1", "Late!", dueDate = today.minus(1, DateTimeUnit.DAY))
        val result = AgendaEvaluator.evaluate(listOf(task), definition, today, context.now)
        assertEquals(1, result.size)
        assertEquals(com.singularity.todo.feature.agenda.domain.model.AgendaBadge.Overdue, result[0].tasks[0].badge)
    }

    @Test
    fun `evaluate skips empty sections`() {
        val definition = AgendaDefinition(
            title = "Test",
            sections = listOf(
                Section(
                    id = "empty",
                    name = "Empty",
                    order = 0,
                    selector = Selector.DateBucket(RelativeBucket.Tomorrow),
                ),
                Section(
                    id = "has-today",
                    name = "Has Today",
                    order = 1,
                    selector = Selector.DateBucket(RelativeBucket.Today),
                ),
            ),
        )
        val tasks = listOf(makeTask("1", "Today task", dueDate = today))
        val result = AgendaEvaluator.evaluate(tasks, definition, today, context.now)
        assertEquals(1, result.size)
        assertEquals("Has Today", result[0].name)
    }

    @Test
    fun `evaluate deduplicates by Task id (UUID identity, not content)`() {
        // Task is a data class — equality includes id (UUID), not just content.
        // This means two tasks with identical title/dates but different UUIDs are
        // distinct, which is the correct semantics for a globally unique identifier.
        val definition = AgendaDefinition(
            title = "Test",
            sections = listOf(
                Section(id = "all", name = "All", order = 0, selector = Selector.Anything),
            ),
        )
        val base = makeTask("base", "Same title", dueDate = today)
        // task with different id but identical content
        val duplicate = Task(
            id = com.singularity.todo.feature.tasks.domain.model.TaskId.generate(),
            title = "Same title",
            dueDate = today,
            priority = base.priority,
            isPinned = base.isPinned,
            tags = base.tags,
            projectId = base.projectId,
            kind = base.kind,
            completedAt = base.completedAt,
            createdAt = base.createdAt,
            updatedAt = base.updatedAt,
            userId = base.userId,
        )
        val tasks = listOf(base, duplicate)
        // Both appear because ids differ — toSet() uses UUID-based equality.
        val result = AgendaEvaluator.evaluate(tasks, definition, today, context.now)
        assertEquals(2, result[0].tasks.size, "two tasks with different UUIDs must both appear")
    }

    @Test
    fun `evaluate deduplicates by object identity when same id is passed twice`() {
        // If the same Task instance (same id) is passed twice, toSet() keeps one.
        // This is the correct semantics — the agenda never shows the same task twice.
        val definition = AgendaDefinition(
            title = "Test",
            sections = listOf(
                Section(id = "all", name = "All", order = 0, selector = Selector.Anything),
            ),
        )
        val task = makeTask("1", "One task", dueDate = today)
        val tasks = listOf(task, task) // same instance twice
        val result = AgendaEvaluator.evaluate(tasks, definition, today, context.now)
        assertEquals(1, result[0].tasks.size, "same Task instance passed twice → one entry")
    }

    @Test
    fun `evaluate sets badge to task count`() {
        val definition = AgendaDefinition(
            title = "Test",
            sections = listOf(
                Section(id = "today", name = "Today", order = 0, selector = Selector.DateBucket(RelativeBucket.Today)),
            ),
        )
        val tasks = listOf(
            makeTask("1", "Task 1", dueDate = today),
            makeTask("2", "Task 2", dueDate = today),
        )
        val result = AgendaEvaluator.evaluate(tasks, definition, today, context.now)
        assertEquals(2, result[0].badge)
    }
}
