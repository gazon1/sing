package com.singularity.todo.feature.tasks.presentation.model

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.presentation.state.TaskBadgesUi
import com.singularity.todo.feature.tasks.presentation.state.UpcomingTaskUi
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [UpcomingTaskUiMapper].
 *
 * Pure transformation — no Compose, no Koin, no mocks.
 */
class UpcomingTaskUiMapperTest {

    private val testUserId = UserId("test-user")
    private val today = LocalDate(2026, 9, 16)

    private fun makeTask(
        id: TaskId = TaskId.generate(),
        title: String = "Test task",
        dueDate: LocalDate? = null,
        description: String? = null,
        completedAt: Instant? = null,
        projectId: ProjectId? = null,
    ): Task = Task(
        id = id,
        title = title,
        dueDate = dueDate,
        description = description,
        completedAt = completedAt,
        projectId = projectId,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
        userId = testUserId,
    )

    @Test
    fun toUpcomingTaskUi_noDueDate_noRecurringLabel() {
        val task = makeTask(dueDate = null)
        val result = UpcomingTaskUiMapper.toUpcomingTaskUi(task, today)

        assertNull(result.recurringLabel)
        assertFalse(result.isRecurring)
    }

    @Test
    fun toUpcomingTaskUi_dueToday_recurringLabelIsToday() {
        val task = makeTask(dueDate = today)
        val result = UpcomingTaskUiMapper.toUpcomingTaskUi(task, today)

        assertEquals("Today", result.recurringLabel)
        assertTrue(result.isRecurring)
    }

    @Test
    fun toUpcomingTaskUi_dueTomorrow_recurringLabelIsTomorrow() {
        val tomorrow = today.plus(1, kotlinx.datetime.DateTimeUnit.DAY)
        val task = makeTask(dueDate = tomorrow)
        val result = UpcomingTaskUiMapper.toUpcomingTaskUi(task, today)

        assertEquals("Tomorrow", result.recurringLabel)
        assertTrue(result.isRecurring)
    }

    @Test
    fun toUpcomingTaskUi_dueInPast_recurringLabelIsAbsoluteDate() {
        val past = LocalDate(2026, 9, 10)
        val task = makeTask(dueDate = past)
        val result = UpcomingTaskUiMapper.toUpcomingTaskUi(task, today)

        assertEquals("10 September 2026", result.recurringLabel)
        assertTrue(result.isRecurring)
    }

    @Test
    fun toUpcomingTaskUi_dueInFuture_recurringLabelIsAbsoluteDate() {
        val future = LocalDate(2026, 10, 1)
        val task = makeTask(dueDate = future)
        val result = UpcomingTaskUiMapper.toUpcomingTaskUi(task, today)

        assertEquals("1 October 2026", result.recurringLabel)
        assertTrue(result.isRecurring)
    }

    @Test
    fun toUpcomingTaskUi_notCompleted_overdue_isOverdue() {
        val past = LocalDate(2026, 9, 10)
        val task = makeTask(dueDate = past, completedAt = null)
        val result = UpcomingTaskUiMapper.toUpcomingTaskUi(task, today)

        assertTrue(result.isOverdue)
    }

    @Test
    fun toUpcomingTaskUi_completed_overdue_isNotOverdue() {
        val past = LocalDate(2026, 9, 10)
        val task = makeTask(dueDate = past, completedAt = Instant.fromEpochMilliseconds(1))
        val result = UpcomingTaskUiMapper.toUpcomingTaskUi(task, today)

        assertFalse(result.isOverdue)
    }

    @Test
    fun toUpcomingTaskUi_completed_isNotOverdue() {
        val task = makeTask(dueDate = today, completedAt = Instant.fromEpochMilliseconds(1))
        val result = UpcomingTaskUiMapper.toUpcomingTaskUi(task, today)

        assertFalse(result.isOverdue)
    }

    @Test
    fun toUpcomingTaskUi_withDescription_hasNoteBadge() {
        val task = makeTask(description = "Some description")
        val result = UpcomingTaskUiMapper.toUpcomingTaskUi(task, today)

        assertTrue(result.badges.hasNote)
    }

    @Test
    fun toUpcomingTaskUi_withoutDescription_noNoteBadge() {
        val task = makeTask(description = null)
        val result = UpcomingTaskUiMapper.toUpcomingTaskUi(task, today)

        assertFalse(result.badges.hasNote)
    }

    @Test
    fun toUpcomingTaskUi_deadlineDate_populated() {
        val dueDate = LocalDate(2026, 10, 1)
        val task = makeTask(dueDate = dueDate)
        val result = UpcomingTaskUiMapper.toUpcomingTaskUi(task, today)

        assertEquals(dueDate, result.badges.deadlineDate)
    }

    @Test
    fun toUpcomingTaskUi_projectName_set() {
        val projectId = ProjectId.fromString("proj-1")
        val task = makeTask(projectId = projectId)
        val result = UpcomingTaskUiMapper.toUpcomingTaskUi(task, today)

        assertEquals("proj-1", result.projectName)
    }
}
