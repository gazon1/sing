package com.singularity.todo.feature.tasks

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.TaskDomain
import com.singularity.todo.feature.tasks.domain.model.CreateTaskInput
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for pure domain logic in TaskDomain.
 * No mocks needed - all functions are pure.
 */
class TasksDomainTest {

    // ===== validateTitle =====

    @Test
    fun `validateTitle - blank string returns Left`() {
        val result = TaskDomain.validateTitle("")
        assertIs<Either.Left<AppError.Validation>>(result)
        assertEquals("Title cannot be blank", result.error.message)
    }

    @Test
    fun `validateTitle - whitespace only returns Left`() {
        val result = TaskDomain.validateTitle("   \t\n")
        assertIs<Either.Left<AppError.Validation>>(result)
    }

    @Test
    fun `validateTitle - valid string returns Right with trimmed title`() {
        val result = TaskDomain.validateTitle("  Buy groceries  ")
        assertIs<Either.Right<String>>(result)
        assertEquals("Buy groceries", result.value)
    }

    // ===== createInput =====

    @Test
    fun `createInput - blank title returns Left`() {
        val result = TaskDomain.createInput(title = "")
        assertIs<Either.Left<AppError.Validation>>(result)
    }

    @Test
    fun `createInput - valid input returns Right with trimmed title`() {
        val result = TaskDomain.createInput(
            title = "  Buy groceries  ",
        )
        assertIs<Either.Right<CreateTaskInput>>(result)
        assertEquals("Buy groceries", result.value.title)
    }

    @Test
    fun `createInput - with all parameters`() {
        val projectId = ProjectId.fromString("proj-1")
        val tagIds = listOf(TagId.fromString("tag-1"), TagId.fromString("tag-2"))
        val dueDate = LocalDate(2024, 1, 15)

        val result = TaskDomain.createInput(
            title = "Task",
            description = "Description",
            priority = TaskPriority.High,
            kind = TaskKind.Task,
            projectId = projectId,
            tagIds = tagIds,
            dueDate = dueDate,
            dueTime = com.singularity.todo.core.database.LocalTimeFormats.parse("14:00:00"),
            someday = false,
        )

        assertIs<Either.Right<CreateTaskInput>>(result)
        val input = result.value
        assertEquals("Task", input.title)
        assertEquals("Description", input.description)
        assertEquals(TaskPriority.High, input.priority)
        assertEquals(projectId, input.projectId)
        assertEquals(tagIds, input.tagIds)
        assertEquals(dueDate, input.dueDate)
        assertEquals(com.singularity.todo.core.database.LocalTimeFormats.parse("14:00:00"), input.dueTime)
    }

    // ===== buildTask =====

    @Test
    fun `buildTask - creates task with correct fields`() {
        val input = CreateTaskInput(
            title = "Test task",
        )
        val createdAt = Instant.fromEpochMilliseconds(1000)
        val updatedAt = Instant.fromEpochMilliseconds(2000)

        val task = TaskDomain.buildTask(
            input = input,
            createdAt = createdAt,
            updatedAt = updatedAt,
            userId = UserId.fromString("user-1"),
        )

        assertEquals("Test task", task.title)
        assertEquals(createdAt, task.createdAt)
        assertEquals(updatedAt, task.updatedAt)
        assertEquals(UserId.fromString("user-1"), task.userId)
        assertEquals(TaskPriority.None, task.priority)
        assertEquals(TaskKind.Task, task.kind)
        assertFalse(task.someday)
        assertFalse(task.isCompleted)
        assertFalse(task.isTrashed)
    }

    // ===== matchesFilter =====

    private fun taskWith(
        dueDate: LocalDate? = null,
        someday: Boolean = false,
        archivedAt: Instant? = null,
        projectId: ProjectId? = null,
        tags: List<TagId> = emptyList(),
        title: String = "Task",
        description: String? = null,
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
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    @Test
    fun `matchesFilter - Today with matching dueDate`() {
        val today = LocalDate(2024, 1, 15)
        val task = taskWith(dueDate = today)
        assertTrue(TaskDomain.matchesFilter(task, TaskFilter.Today, today))
    }

    @Test
    fun `matchesFilter - Today with different dueDate`() {
        val today = LocalDate(2024, 1, 15)
        val task = taskWith(dueDate = LocalDate(2024, 1, 16))
        assertFalse(TaskDomain.matchesFilter(task, TaskFilter.Today, today))
    }

    @Test
    fun `matchesFilter - Today ignores trashed`() {
        val today = LocalDate(2024, 1, 15)
        val task = taskWith(
            dueDate = today,
            archivedAt = Instant.fromEpochMilliseconds(1),
        )
        assertFalse(TaskDomain.matchesFilter(task, TaskFilter.Today, today))
    }

    @Test
    fun `matchesFilter - Upcoming with future dueDate`() {
        val today = LocalDate(2024, 1, 15)
        val task = taskWith(dueDate = LocalDate(2024, 1, 20))
        assertTrue(TaskDomain.matchesFilter(task, TaskFilter.Upcoming, today))
    }

    @Test
    fun `matchesFilter - Upcoming excludes today`() {
        val today = LocalDate(2024, 1, 15)
        val task = taskWith(dueDate = today)
        assertFalse(TaskDomain.matchesFilter(task, TaskFilter.Upcoming, today))
    }

    @Test
    fun `matchesFilter - Someday`() {
        val today = LocalDate(2024, 1, 15)
        val task = taskWith(someday = true)
        assertTrue(TaskDomain.matchesFilter(task, TaskFilter.Someday, today))
    }

    @Test
    fun `matchesFilter - Inbox excludes someday and trashed`() {
        val today = LocalDate(2024, 1, 15)
        val activeTask = taskWith(someday = false)
        assertTrue(TaskDomain.matchesFilter(activeTask, TaskFilter.Inbox, today))

        val somedayTask = taskWith(someday = true)
        assertFalse(TaskDomain.matchesFilter(somedayTask, TaskFilter.Inbox, today))

        val trashedTask = taskWith(archivedAt = Instant.fromEpochMilliseconds(1))
        assertFalse(TaskDomain.matchesFilter(trashedTask, TaskFilter.Inbox, today))
    }

    @Test
    fun `matchesFilter - Trash`() {
        val today = LocalDate(2024, 1, 15)
        val activeTask = taskWith()
        val trashedTask = taskWith(archivedAt = Instant.fromEpochMilliseconds(1))
        // Trash filter shows only trashed tasks
        assertFalse(TaskDomain.matchesFilter(activeTask, TaskFilter.Trash, today))
        assertTrue(TaskDomain.matchesFilter(trashedTask, TaskFilter.Trash, today))
    }

    @Test
    fun `matchesFilter - ByProject`() {
        val today = LocalDate(2024, 1, 15)
        val projectId = ProjectId.fromString("proj-1")
        val task = taskWith(projectId = projectId)
        assertTrue(TaskDomain.matchesFilter(task, TaskFilter.ByProject(projectId), today))
        assertFalse(TaskDomain.matchesFilter(task, TaskFilter.ByProject(ProjectId.fromString("other")), today))
    }

    @Test
    fun `matchesFilter - ByTag`() {
        val today = LocalDate(2024, 1, 15)
        val tagId = TagId.fromString("tag-1")
        val task = taskWith(tags = listOf(tagId))
        assertTrue(TaskDomain.matchesFilter(task, TaskFilter.ByTag(tagId), today))
        assertFalse(TaskDomain.matchesFilter(task, TaskFilter.ByTag(TagId.fromString("other")), today))
    }

    @Test
    fun `matchesFilter - Search by title`() {
        val today = LocalDate(2024, 1, 15)
        val task = taskWith(title = "Buy groceries")
        assertTrue(TaskDomain.matchesFilter(task, TaskFilter.Search("groceries"), today))
        assertTrue(TaskDomain.matchesFilter(task, TaskFilter.Search("BUY"), today))
        assertFalse(TaskDomain.matchesFilter(task, TaskFilter.Search("electronics"), today))
    }

    @Test
    fun `matchesFilter - Search by description`() {
        val today = LocalDate(2024, 1, 15)
        val task = taskWith(title = "Task", description = "Buy milk and eggs")
        assertTrue(TaskDomain.matchesFilter(task, TaskFilter.Search("milk"), today))
        assertFalse(TaskDomain.matchesFilter(task, TaskFilter.Search("bread"), today))
    }

    @Test
    fun `matchesFilter - Search is case insensitive`() {
        val today = LocalDate(2024, 1, 15)
        val task = taskWith(title = "Buy Groceries")
        assertTrue(TaskDomain.matchesFilter(task, TaskFilter.Search("groceries"), today))
        assertTrue(TaskDomain.matchesFilter(task, TaskFilter.Search("BUY GROCERIES"), today))
    }
}
