package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.feature.agenda.domain.logic.AgendaEvaluator
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.Section
import com.singularity.todo.feature.agenda.domain.model.Selector
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgendaEvaluatorTest {

    // Fixed date so tests are deterministic regardless of the machine's system date.
    private val today: LocalDate = LocalDate(2026, Month.SEPTEMBER, 16)

    private fun makeTask(
        id: String,
        title: String,
        dueDate: LocalDate? = null,
        priority: TaskPriority = TaskPriority.None,
        isCompleted: Boolean = false,
        isPinned: Boolean = false,
        tags: List<TagId> = emptyList(),
        projectId: ProjectId? = null,
    ): Task =
        Task(
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
            updatedAt = kotlin.time.Instant.fromEpochMilliseconds(0),
            userId = com.singularity.todo.core.ids.UserId("test-user"),
        )

    // ─── Selector: DateBucket ──────────────────────────────────────────────────

    @Test
    fun `DateBucket Today matches task due today`() {
        val task = makeTask("1", "Buy milk", dueDate = today)
        assertTrue(AgendaEvaluator.matches(task, Selector.DateBucket(RelativeBucket.Today), today))
    }

    @Test
    fun `DateBucket Today does not match task due yesterday`() {
        val task = makeTask("1", "Buy milk", dueDate = today.minus(1, DateTimeUnit.DAY))
        assertFalse(AgendaEvaluator.matches(task, Selector.DateBucket(RelativeBucket.Today), today))
    }

    @Test
    fun `DateBucket ThisWeek matches task within current week`() {
        val task = makeTask("1", "Buy milk", dueDate = today.plus(2, DateTimeUnit.DAY))
        assertTrue(AgendaEvaluator.matches(task, Selector.DateBucket(RelativeBucket.ThisWeek), today))
    }

    @Test
    fun `DateBucket Overdue matches past incomplete task`() {
        val task = makeTask("1", "Buy milk", dueDate = today.minus(1, DateTimeUnit.DAY), isCompleted = false)
        assertTrue(AgendaEvaluator.matches(task, Selector.DateBucket(RelativeBucket.Overdue), today))
    }

    @Test
    fun `DateBucket Overdue does not match completed overdue task`() {
        val task = makeTask("1", "Buy milk", dueDate = today.minus(1, DateTimeUnit.DAY), isCompleted = true)
        assertFalse(AgendaEvaluator.matches(task, Selector.DateBucket(RelativeBucket.Overdue), today))
    }

    @Test
    fun `DateBucket NoDate matches task without due date`() {
        val task = makeTask("1", "Buy milk", dueDate = null)
        assertTrue(AgendaEvaluator.matches(task, Selector.DateBucket(RelativeBucket.NoDate), today))
    }

    @Test
    fun `DateBucket NoDate does not match task with due date`() {
        val task = makeTask("1", "Buy milk", dueDate = today)
        assertFalse(AgendaEvaluator.matches(task, Selector.DateBucket(RelativeBucket.NoDate), today))
    }

    // ─── Selector: Statuses ────────────────────────────────────────────────────

    @Test
    fun `Statuses Active matches incomplete task`() {
        val task = makeTask("1", "Active task", isCompleted = false)
        assertTrue(AgendaEvaluator.matches(task, Selector.Statuses(setOf(TaskStatus.Active)), today))
    }

    @Test
    fun `Statuses Active does not match completed task`() {
        val task = makeTask("1", "Done task", isCompleted = true)
        assertFalse(AgendaEvaluator.matches(task, Selector.Statuses(setOf(TaskStatus.Active)), today))
    }

    @Test
    fun `Statuses Completed matches completed task`() {
        val task = makeTask("1", "Done task", isCompleted = true)
        assertTrue(AgendaEvaluator.matches(task, Selector.Statuses(setOf(TaskStatus.Completed)), today))
    }

    @Test
    fun `Statuses All matches any non-trashed task`() {
        val active = makeTask("1", "Active", isCompleted = false)
        val done = makeTask("2", "Done", isCompleted = true)
        assertTrue(AgendaEvaluator.matches(active, Selector.Statuses(setOf(TaskStatus.All)), today))
        assertTrue(AgendaEvaluator.matches(done, Selector.Statuses(setOf(TaskStatus.All)), today))
    }

    @Test
    fun `Statuses ActivePlusCompleted equals All`() {
        val active = makeTask("1", "Active", isCompleted = false)
        val done = makeTask("2", "Done", isCompleted = true)
        val statuses = setOf(TaskStatus.Active, TaskStatus.Completed)
        assertTrue(AgendaEvaluator.matches(active, Selector.Statuses(statuses), today))
        assertTrue(AgendaEvaluator.matches(done, Selector.Statuses(statuses), today))
    }

    // ─── Selector: Priorities ─────────────────────────────────────────────────

    @Test
    fun `Priorities atMost matches task in set`() {
        val task = makeTask("1", "High priority", priority = TaskPriority.High)
        val selector = Selector.Priorities(setOf(TaskPriority.High, TaskPriority.Urgent))
        assertTrue(AgendaEvaluator.matches(task, selector, today))
    }

    @Test
    fun `Priorities atMost does not match task outside set`() {
        val task = makeTask("1", "Low priority", priority = TaskPriority.Low)
        val selector = Selector.Priorities(setOf(TaskPriority.High, TaskPriority.Urgent))
        assertFalse(AgendaEvaluator.matches(task, selector, today))
    }

    @Test
    fun `Priorities notAtMost matches task outside set`() {
        val task = makeTask("1", "Low priority", priority = TaskPriority.Low)
        val selector = Selector.Priorities(setOf(TaskPriority.High, TaskPriority.Urgent), atMost = false)
        assertTrue(AgendaEvaluator.matches(task, selector, today))
    }

    // ─── Selector: Tags ───────────────────────────────────────────────────────

    @Test
    fun `Tags matches task with that tag`() {
        val tag = TagId.fromString("tag-1")
        val task = makeTask("1", "Tagged task", tags = listOf(tag))
        assertTrue(AgendaEvaluator.matches(task, Selector.Tags(setOf(tag)), today))
    }

    @Test
    fun `Tags does not match task without that tag`() {
        val tag = TagId.fromString("tag-1")
        val task = makeTask("1", "Untagged task", tags = emptyList())
        assertFalse(AgendaEvaluator.matches(task, Selector.Tags(setOf(tag)), today))
    }

    // ─── Selector: Projects ──────────────────────────────────────────────────

    @Test
    fun `Projects matches task in set`() {
        val projectId = ProjectId.fromString("proj-1")
        val task = makeTask("1", "Project task", projectId = projectId)
        assertTrue(AgendaEvaluator.matches(task, Selector.Projects(setOf(projectId)), today))
    }

    @Test
    fun `Projects does not match task in different project`() {
        val projectId = ProjectId.fromString("proj-1")
        val task = makeTask("1", "Other project task", projectId = ProjectId.fromString("proj-2"))
        assertFalse(AgendaEvaluator.matches(task, Selector.Projects(setOf(projectId)), today))
    }

    // ─── Selector: Pinned / Completed ─────────────────────────────────────────

    @Test
    fun `Pinned matches pinned task`() {
        val task = makeTask("1", "Pinned task", isPinned = true)
        assertTrue(AgendaEvaluator.matches(task, Selector.Pinned, today))
    }

    @Test
    fun `Pinned does not match unpinned task`() {
        val task = makeTask("1", "Unpinned task", isPinned = false)
        assertFalse(AgendaEvaluator.matches(task, Selector.Pinned, today))
    }

    @Test
    fun `Completed matches completed task`() {
        val task = makeTask("1", "Done task", isCompleted = true)
        assertTrue(AgendaEvaluator.matches(task, Selector.Completed, today))
    }

    // ─── Selector: Overdue ───────────────────────────────────────────────────

    @Test
    fun `Overdue matches past incomplete task`() {
        val task = makeTask("1", "Late!", dueDate = today.minus(1, DateTimeUnit.DAY), isCompleted = false)
        assertTrue(AgendaEvaluator.matches(task, Selector.Overdue, today))
    }

    @Test
    fun `Overdue does not match future task`() {
        val task = makeTask("1", "Future task", dueDate = today.plus(1, DateTimeUnit.DAY))
        assertFalse(AgendaEvaluator.matches(task, Selector.Overdue, today))
    }

    // ─── Selector: Regexp ─────────────────────────────────────────────────────

    @Test
    fun `Regexp matches title pattern`() {
        val task = makeTask("1", "Buy groceries at the store")
        assertTrue(AgendaEvaluator.matches(task, Selector.Regexp("buy.*store"), today))
    }

    @Test
    fun `Regexp is case insensitive`() {
        val task = makeTask("1", "Buy groceries")
        assertTrue(AgendaEvaluator.matches(task, Selector.Regexp("BUY"), today))
    }

    @Test
    fun `Regexp does not match non-matching title`() {
        val task = makeTask("1", "Sell stuff")
        assertFalse(AgendaEvaluator.matches(task, Selector.Regexp("buy"), today))
    }

    // ─── Selector: AllOf / AnyOf / Not ────────────────────────────────────────

    @Test
    fun `AllOf matches when all children match`() {
        val task = makeTask("1", "Active pinned", isCompleted = false, isPinned = true)
        val selector = Selector.AllOf(listOf(Selector.Pinned, Selector.Statuses(setOf(TaskStatus.Active))))
        assertTrue(AgendaEvaluator.matches(task, selector, today))
    }

    @Test
    fun `AllOf does not match when one child fails`() {
        val task = makeTask("1", "Active unpinned", isCompleted = false, isPinned = false)
        val selector = Selector.AllOf(listOf(Selector.Pinned, Selector.Statuses(setOf(TaskStatus.Active))))
        assertFalse(AgendaEvaluator.matches(task, selector, today))
    }

    @Test
    fun `AnyOf matches when at least one child matches`() {
        val task = makeTask("1", "Active unpinned", isCompleted = false, isPinned = false)
        val selector = Selector.AnyOf(listOf(Selector.Pinned, Selector.Statuses(setOf(TaskStatus.Active))))
        assertTrue(AgendaEvaluator.matches(task, selector, today))
    }

    @Test
    fun `AnyOf does not match when all children fail`() {
        val task = makeTask("1", "Completed unpinned", isCompleted = true, isPinned = false)
        val selector = Selector.AnyOf(listOf(Selector.Pinned, Selector.Statuses(setOf(TaskStatus.Active))))
        assertFalse(AgendaEvaluator.matches(task, selector, today))
    }

    @Test
    fun `Not inverts child match`() {
        val task = makeTask("1", "Pinned task", isPinned = true)
        assertFalse(AgendaEvaluator.matches(task, Selector.Not(Selector.Pinned), today))
        assertTrue(AgendaEvaluator.matches(task, Selector.Not(Selector.Completed), today))
    }

    // ─── Selector: Anything ───────────────────────────────────────────────────

    @Test
    fun `Anything matches any task`() {
        val task = makeTask("1", "Any task")
        assertTrue(AgendaEvaluator.matches(task, Selector.Anything, today))
    }

    // ─── AgendaEvaluator.evaluate ─────────────────────────────────────────────

    @Test
    fun `evaluate produces sections in order`() {
        val definition = AgendaDefinition(
            title = "Test",
            sections = listOf(
                Section("Second", order = 1, selector = Selector.DateBucket(RelativeBucket.Tomorrow)),
                Section("First", order = 0, selector = Selector.DateBucket(RelativeBucket.Today)),
            ),
        )
        val tasks = listOf(
            makeTask("1", "Today task", dueDate = today),
            makeTask("2", "Tomorrow task", dueDate = today.plus(1, DateTimeUnit.DAY)),
        )
        val result = AgendaEvaluator.evaluate(tasks, definition, today)
        assertEquals(2, result.size)
        assertEquals("First", result[0].name)
        assertEquals("Second", result[1].name)
    }

    @Test
    fun `evaluate applies discard semantics`() {
        val definition = AgendaDefinition(
            title = "Test",
            sections = listOf(
                Section("All", order = 0, selector = Selector.Anything, discard = true), // discard removes matched tasks
                Section("Only Overdue", order = 1, selector = Selector.DateBucket(RelativeBucket.Overdue)),
            ),
        )
        val tasks = listOf(
            makeTask("1", "Overdue task", dueDate = today.minus(1, DateTimeUnit.DAY)),
            makeTask("2", "Today task", dueDate = today),
        )
        val result = AgendaEvaluator.evaluate(tasks, definition, today)
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
                Section("Overdue", order = 0, selector = Selector.DateBucket(RelativeBucket.Overdue)),
            ),
        )
        val task = makeTask("1", "Late!", dueDate = today.minus(1, DateTimeUnit.DAY))
        val result = AgendaEvaluator.evaluate(listOf(task), definition, today)
        assertEquals(1, result.size)
        assertEquals(com.singularity.todo.feature.agenda.domain.model.AgendaBadge.Overdue, result[0].tasks[0].badge)
    }

    @Test
    fun `evaluate skips empty sections`() {
        val definition = AgendaDefinition(
            title = "Test",
            sections = listOf(
                Section("Empty", order = 0, selector = Selector.DateBucket(RelativeBucket.Tomorrow)),
                Section("Has Today", order = 1, selector = Selector.DateBucket(RelativeBucket.Today)),
            ),
        )
        val tasks = listOf(makeTask("1", "Today task", dueDate = today))
        val result = AgendaEvaluator.evaluate(tasks, definition, today)
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
                Section("All", order = 0, selector = Selector.Anything),
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
        val result = AgendaEvaluator.evaluate(tasks, definition, today)
        assertEquals(2, result[0].tasks.size, "two tasks with different UUIDs must both appear")
    }

    @Test
    fun `evaluate deduplicates by object identity when same id is passed twice`() {
        // If the same Task instance (same id) is passed twice, toSet() keeps one.
        // This is the correct semantics — the agenda never shows the same task twice.
        val definition = AgendaDefinition(
            title = "Test",
            sections = listOf(
                Section("All", order = 0, selector = Selector.Anything),
            ),
        )
        val task = makeTask("1", "One task", dueDate = today)
        val tasks = listOf(task, task) // same instance twice
        val result = AgendaEvaluator.evaluate(tasks, definition, today)
        assertEquals(1, result[0].tasks.size, "same Task instance passed twice → one entry")
    }

    @Test
    fun `evaluate sets badge to task count`() {
        val definition = AgendaDefinition(
            title = "Test",
            sections = listOf(
                Section("Today", order = 0, selector = Selector.DateBucket(RelativeBucket.Today)),
            ),
        )
        val tasks = listOf(
            makeTask("1", "Task 1", dueDate = today),
            makeTask("2", "Task 2", dueDate = today),
        )
        val result = AgendaEvaluator.evaluate(tasks, definition, today)
        assertEquals(2, result[0].badge)
    }
}
