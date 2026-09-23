package com.singularity.todo.feature.tasks

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.logic.TaskComputed
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [TaskComputed] derived predicates.
 */
class TaskComputedTest {

    private val testUserId = UserId("test-user")
    private val today = LocalDate(2026, Month.SEPTEMBER, 16)

    private fun task(
        dueDate: LocalDate? = today,
        dueTime: LocalTime? = null,
        startDate: LocalDate? = null,
        startTime: LocalTime? = null,
        endDate: LocalDate? = null,
        endTime: LocalTime? = null,
        completedAt: Instant? = null,
        archivedAt: Instant? = null,
    ): Task = Task(
        id = TaskId.generate(),
        title = "Test",
        description = null,
        priority = com.singularity.todo.feature.tasks.domain.model.TaskPriority.None,
        kind = com.singularity.todo.feature.tasks.domain.model.TaskKind.Task,
        projectId = null,
        parentTaskId = null,
        tags = emptyList(),
        dueDate = dueDate,
        dueTime = dueTime,
        startDate = startDate,
        startTime = startTime,
        endDate = endDate,
        endTime = endTime,
        accentColor = null,
        emoji = null,
        completedAt = completedAt,
        someday = false,
        archivedAt = archivedAt,
        isPinned = false,
        dependsOn = emptySet(),
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
        userId = testUserId,
    )

    // ─── isActive ────────────────────────────────────────────────────────────

    @Test
    fun `isActive true when no startDate or endDate and not completed`() {
        val t = task(completedAt = null, archivedAt = null)
        assertTrue(TaskComputed.isActive(t, today))
    }

    @Test
    fun `isActive false when completed`() {
        val t = task(completedAt = Instant.fromEpochMilliseconds(1))
        assertFalse(TaskComputed.isActive(t, today))
    }

    @Test
    fun `isActive false when trashed`() {
        val t = task(archivedAt = Instant.fromEpochMilliseconds(1))
        assertFalse(TaskComputed.isActive(t, today))
    }

    @Test
    fun `isActive true when today is on or after startDate`() {
        val t = task(startDate = LocalDate(2026, Month.SEPTEMBER, 10))
        assertTrue(TaskComputed.isActive(t, today))
    }

    @Test
    fun `isActive false when today is before startDate`() {
        val t = task(startDate = LocalDate(2026, Month.SEPTEMBER, 20))
        assertFalse(TaskComputed.isActive(t, today))
    }

    @Test
    fun `isActive true when today is on or before endDate`() {
        val t = task(endDate = LocalDate(2026, Month.SEPTEMBER, 20))
        assertTrue(TaskComputed.isActive(t, today))
    }

    @Test
    fun `isActive false when today is after endDate`() {
        val t = task(endDate = LocalDate(2026, Month.SEPTEMBER, 10))
        assertFalse(TaskComputed.isActive(t, today))
    }

    @Test
    fun `isActive true when today is within startDate and endDate`() {
        val t = task(
            startDate = LocalDate(2026, Month.SEPTEMBER, 10),
            endDate = LocalDate(2026, Month.SEPTEMBER, 20),
        )
        assertTrue(TaskComputed.isActive(t, today))
    }

    @Test
    fun `isActive false when today is before startDate even with endDate`() {
        val t = task(
            startDate = LocalDate(2026, Month.SEPTEMBER, 20),
            endDate = LocalDate(2026, Month.SEPTEMBER, 30),
        )
        assertFalse(TaskComputed.isActive(t, today))
    }

    @Test
    fun `isActive false when today is after endDate even with startDate`() {
        val t = task(
            startDate = LocalDate(2026, Month.SEPTEMBER, 1),
            endDate = LocalDate(2026, Month.SEPTEMBER, 10),
        )
        assertFalse(TaskComputed.isActive(t, today))
    }

    @Test
    fun `isActive uses only startDate when endDate is null`() {
        // startDate in past → active
        assertTrue(TaskComputed.isActive(task(startDate = LocalDate(2026, Month.SEPTEMBER, 10)), today))
        // startDate in future → not active
        assertFalse(TaskComputed.isActive(task(startDate = LocalDate(2026, Month.SEPTEMBER, 20)), today))
    }

    @Test
    fun `isActive uses only endDate when startDate is null`() {
        // endDate in future → active
        assertTrue(TaskComputed.isActive(task(endDate = LocalDate(2026, Month.SEPTEMBER, 20)), today))
        // endDate in past → not active
        assertFalse(TaskComputed.isActive(task(endDate = LocalDate(2026, Month.SEPTEMBER, 10)), today))
    }
}
