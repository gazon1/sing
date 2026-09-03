package com.singularity.todo.feature.tasks

import com.singularity.todo.core.error.AppError
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tags.TagId
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for pure domain logic in TasksDomain.
 * No mocks needed - all functions are pure.
 */
class TasksDomainTest {

    // ===== validateTitle =====

    @Test
    fun `validateTitle - blank string throws Validation`() {
        assertFailsWith<AppError.Validation> {
            TasksDomain.validateTitle("")
        }
    }

    @Test
    fun `validateTitle - whitespace only throws Validation`() {
        assertFailsWith<AppError.Validation> {
            TasksDomain.validateTitle("   \t\n")
        }
    }

    @Test
    fun `validateTitle - valid string does not throw`() {
        TasksDomain.validateTitle("Buy groceries")
    }

    // ===== createInput =====

    @Test
    fun `createInput - blank title throws Validation`() {
        assertFailsWith<AppError.Validation> {
            TasksDomain.createInput(title = "", userId = UserId.anonymous)
        }
    }

    @Test
    fun `createInput - valid input returns trimmed title`() {
        val input = TasksDomain.createInput(
            title = "  Buy groceries  ",
            userId = UserId.anonymous
        )
        assertEquals("Buy groceries", input.title)
    }

    @Test
    fun `createInput - with all parameters`() {
        val projectId = ProjectId.fromString("proj-1")
        val tagIds = listOf(TagId.fromString("tag-1"), TagId.fromString("tag-2"))
        val dueDate = LocalDate(2024, 1, 15)

        val input = TasksDomain.createInput(
            title = "Task",
            description = "Description",
            priority = TaskPriority.High,
            kind = TaskKind.Task,
            projectId = projectId,
            tagIds = tagIds,
            dueDate = dueDate,
            dueTime = "14:00",
            someday = false,
            userId = UserId.anonymous
        )

        assertEquals("Task", input.title)
        assertEquals("Description", input.description)
        assertEquals(TaskPriority.High, input.priority)
        assertEquals(projectId, input.projectId)
        assertEquals(tagIds, input.tagIds)
        assertEquals(dueDate, input.dueDate)
        assertEquals("14:00", input.dueTime)
    }

    // ===== buildTask =====

    @Test
    fun `buildTask - creates task with correct fields`() {
        val input = CreateTaskInput(
            title = "Test task",
            userId = UserId.fromString("user-1")
        )
        val createdAt = Instant.fromEpochMilliseconds(1000)
        val updatedAt = Instant.fromEpochMilliseconds(2000)

        val task = TasksDomain.buildTask(input, createdAt = createdAt, updatedAt = updatedAt)

        assertEquals("Test task", task.title)
        assertEquals(createdAt, task.createdAt)
        assertEquals(updatedAt, task.updatedAt)
        assertEquals(TaskPriority.None, task.priority)
        assertEquals(TaskKind.Task, task.kind)
        assertFalse(task.someday)
        assertFalse(task.isCompleted)
        assertFalse(task.isTrashed)
    }

    // ===== matchesFilter =====

    private fun taskWith(
        dueDate: kotlinx.datetime.LocalDate? = null,
        someday: Boolean = false,
        archivedAt: Instant? = null,
        projectId: ProjectId? = null,
        tags: List<TagId> = emptyList(),
        title: String = "Task",
        description: String? = null
    ) = Task(
        id = TaskId.generate(),
        title = title,
        description = description,
        userId = UserId.anonymous,
        dueDate = dueDate,
        someday = someday,
        archivedAt = archivedAt,
        projectId = projectId,
        tags = tags,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0)
    )

    @Test
    fun `matchesFilter - Today with matching dueDate`() {
        val today = LocalDate(2024, 1, 15)
        val task = taskWith(dueDate = today)
        assertTrue(TasksDomain.matchesFilter(task, TaskFilter.Today, today))
    }

    @Test
    fun `matchesFilter - Today with different dueDate`() {
        val today = LocalDate(2024, 1, 15)
        val task = taskWith(dueDate = LocalDate(2024, 1, 16))
        assertFalse(TasksDomain.matchesFilter(task, TaskFilter.Today, today))
    }

    @Test
    fun `matchesFilter - Today ignores trashed`() {
        val today = LocalDate(2024, 1, 15)
        val task = taskWith(
            dueDate = today,
            archivedAt = Instant.fromEpochMilliseconds(1)
        )
        assertFalse(TasksDomain.matchesFilter(task, TaskFilter.Today, today))
    }

    @Test
    fun `matchesFilter - Upcoming with future dueDate`() {
        val today = LocalDate(2024, 1, 15)
        val task = taskWith(dueDate = LocalDate(2024, 1, 20))
        assertTrue(TasksDomain.matchesFilter(task, TaskFilter.Upcoming, today))
    }

    @Test
    fun `matchesFilter - Upcoming excludes today`() {
        val today = LocalDate(2024, 1, 15)
        val task = taskWith(dueDate = today)
        assertFalse(TasksDomain.matchesFilter(task, TaskFilter.Upcoming, today))
    }

    @Test
    fun `matchesFilter - Someday`() {
        val today = LocalDate(2024, 1, 15)
        val task = taskWith(someday = true)
        assertTrue(TasksDomain.matchesFilter(task, TaskFilter.Someday, today))
    }

    @Test
    fun `matchesFilter - Inbox excludes someday and trashed`() {
        val today = LocalDate(2024, 1, 15)
        val activeTask = taskWith(someday = false)
        assertTrue(TasksDomain.matchesFilter(activeTask, TaskFilter.Inbox, today))

        val somedayTask = taskWith(someday = true)
        assertFalse(TasksDomain.matchesFilter(somedayTask, TaskFilter.Inbox, today))

        val trashedTask = taskWith(archivedAt = Instant.fromEpochMilliseconds(1))
        assertFalse(TasksDomain.matchesFilter(trashedTask, TaskFilter.Inbox, today))
    }

    @Test
    fun `matchesFilter - Trash`() {
        val today = LocalDate(2024, 1, 15)
        val trashedTask = taskWith(archivedAt = Instant.fromEpochMilliseconds(1))
        assertTrue(TasksDomain.matchesFilter(trashedTask, TaskFilter.Trash, today))

        val activeTask = taskWith()
        assertFalse(TasksDomain.matchesFilter(activeTask, TaskFilter.Trash, today))
    }

    @Test
    fun `matchesFilter - ByProject`() {
        val today = LocalDate(2024, 1, 15)
        val projectId = ProjectId.fromString("proj-1")
        val task = taskWith(projectId = projectId)
        assertTrue(TasksDomain.matchesFilter(task, TaskFilter.ByProject(projectId), today))
        assertFalse(TasksDomain.matchesFilter(task, TaskFilter.ByProject(ProjectId.fromString("other")), today))
    }

    @Test
    fun `matchesFilter - ByTag`() {
        val today = LocalDate(2024, 1, 15)
        val tagId = TagId.fromString("tag-1")
        val task = taskWith(tags = listOf(tagId))
        assertTrue(TasksDomain.matchesFilter(task, TaskFilter.ByTag(tagId), today))
        assertFalse(TasksDomain.matchesFilter(task, TaskFilter.ByTag(TagId.fromString("other")), today))
    }

    @Test
    fun `matchesFilter - Search by title`() {
        val today = LocalDate(2024, 1, 15)
        val task = taskWith(title = "Buy groceries")
        assertTrue(TasksDomain.matchesFilter(task, TaskFilter.Search("groceries"), today))
        assertTrue(TasksDomain.matchesFilter(task, TaskFilter.Search("BUY"), today))
        assertFalse(TasksDomain.matchesFilter(task, TaskFilter.Search("electronics"), today))
    }

    @Test
    fun `matchesFilter - Search by description`() {
        val today = LocalDate(2024, 1, 15)
        val task = taskWith(title = "Task", description = "Buy milk and eggs")
        assertTrue(TasksDomain.matchesFilter(task, TaskFilter.Search("milk"), today))
        assertFalse(TasksDomain.matchesFilter(task, TaskFilter.Search("bread"), today))
    }

    @Test
    fun `matchesFilter - Search is case insensitive`() {
        val today = LocalDate(2024, 1, 15)
        val task = taskWith(title = "Buy Groceries")
        assertTrue(TasksDomain.matchesFilter(task, TaskFilter.Search("groceries"), today))
        assertTrue(TasksDomain.matchesFilter(task, TaskFilter.Search("BUY GROCERIES"), today))
    }
}
