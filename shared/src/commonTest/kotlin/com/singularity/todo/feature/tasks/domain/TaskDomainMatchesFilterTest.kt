package com.singularity.todo.feature.tasks.domain

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@Tag("fast")
class TaskDomainMatchesFilterTest {

    private val today = LocalDate(2026, 9, 16)

    private fun makeTask(
        id: String = "t1",
        completedAt: Instant? = null,
        dueDate: kotlinx.datetime.LocalDate? = today,
        isTrashed: Boolean = false,
        someday: Boolean = false,
    ): Task = Task(
        id = TaskId.fromString(id),
        title = "Test task",
        completedAt = completedAt,
        dueDate = dueDate,
        archivedAt = if (isTrashed) Instant.fromEpochMilliseconds(1_000_000) else null,
        someday = someday,
        priority = TaskPriority.None,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
        userId = UserId("u1"),
    )

    // ─── ByStatuses tests ────────────────────────────────────────────────────

    @Test
    fun `ByStatuses with All matches any non-trashed task`() {
        val active = makeTask(completedAt = null)
        val done = makeTask(completedAt = Instant.fromEpochMilliseconds(1))
        val filter = com.singularity.todo.feature.tasks.domain.model.TaskFilter.ByStatuses(setOf(TaskStatus.All))

        assertTrue(TaskDomain.matchesFilter(active, filter, today), "Active task should match All")
        assertTrue(TaskDomain.matchesFilter(done, filter, today), "Completed task should match All")
    }

    @Test
    fun `ByStatuses with Active matches only non-completed tasks`() {
        val active = makeTask(completedAt = null)
        val done = makeTask(completedAt = Instant.fromEpochMilliseconds(1))
        val filter = com.singularity.todo.feature.tasks.domain.model.TaskFilter.ByStatuses(setOf(TaskStatus.Active))

        assertTrue(TaskDomain.matchesFilter(active, filter, today), "Active task should match Active")
        assertFalse(TaskDomain.matchesFilter(done, filter, today), "Completed task should NOT match Active")
    }

    @Test
    fun `ByStatuses with Completed matches only completed tasks`() {
        val active = makeTask(completedAt = null)
        val done = makeTask(completedAt = Instant.fromEpochMilliseconds(1))
        val filter = com.singularity.todo.feature.tasks.domain.model.TaskFilter.ByStatuses(setOf(TaskStatus.Completed))

        assertFalse(TaskDomain.matchesFilter(active, filter, today), "Active task should NOT match Completed")
        assertTrue(TaskDomain.matchesFilter(done, filter, today), "Completed task should match Completed")
    }

    @Test
    fun `ByStatuses with Active plus Completed is equivalent to All`() {
        val active = makeTask(completedAt = null)
        val done = makeTask(completedAt = Instant.fromEpochMilliseconds(1))
        val filterBoth = com.singularity.todo.feature.tasks.domain.model.TaskFilter.ByStatuses(
            setOf(TaskStatus.Active, TaskStatus.Completed),
        )
        val filterAll = com.singularity.todo.feature.tasks.domain.model.TaskFilter.ByStatuses(setOf(TaskStatus.All))

        assertTrue(
            TaskDomain.matchesFilter(active, filterBoth, today) == TaskDomain.matchesFilter(active, filterAll, today),
        )
        assertTrue(
            TaskDomain.matchesFilter(done, filterBoth, today) == TaskDomain.matchesFilter(done, filterAll, today),
        )
    }

    @Test
    fun `ByStatuses ignores trashed tasks`() {
        val trashedActive = makeTask(completedAt = null, isTrashed = true)
        val trashedDone = makeTask(completedAt = Instant.fromEpochMilliseconds(1), isTrashed = true)
        val filter = com.singularity.todo.feature.tasks.domain.model.TaskFilter.ByStatuses(setOf(TaskStatus.All))

        assertFalse(TaskDomain.matchesFilter(trashedActive, filter, today), "Trashed active should NOT match")
        assertFalse(TaskDomain.matchesFilter(trashedDone, filter, today), "Trashed completed should NOT match")
    }

    @Test
    fun `ByStatuses empty set matches nothing`() {
        val active = makeTask(completedAt = null)
        val filter = com.singularity.todo.feature.tasks.domain.model.TaskFilter.ByStatuses(emptySet())

        assertFalse(TaskDomain.matchesFilter(active, filter, today), "Empty status set should match nothing")
    }
}
