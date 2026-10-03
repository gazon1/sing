package com.singularity.todo.feature.agenda.domain

import com.singularity.todo.feature.agenda.domain.logic.computeAgendaBadge
import com.singularity.todo.feature.tasks.domain.logic.TaskComputed.isBlocked
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.core.ids.UserId
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [computeAgendaBadge] and [isBlocked] for all [AgendaBadge] variants.
 *
 * Badge priority (first-match wins):
 * 1. Blocked   — incomplete dependency
 * 2. Pinned    — isPinned
 * 3. Recurring — recurrence != null
 * 4. Completed — isCompleted
 * 5. Overdue   — dueDate < today AND not completed
 * 6. NoDate    — dueDate == null
 * (null — normal in-date task)
 */
class AgendaBadgePolicyTest {

    private val today = LocalDate(2026, 10, 14) // reference "today"
    private val epoch0 = kotlin.time.Instant.fromEpochMilliseconds(0)

    private fun t(
        id: String,
        dueDate: LocalDate? = null,
        completed: Boolean = false,
        pinned: Boolean = false,
        recurring: Boolean = false,
        dependsOn: List<String> = emptyList(),
    ): Task = Task(
        id = TaskId(id),
        title = "Task $id",
        createdAt = epoch0,
        updatedAt = epoch0,
        userId = UserId("test"),
        kind = TaskKind.Task,
        priority = TaskPriority.None,
        completedAt = if (completed) epoch0 else null,
        archivedAt = null,
        someday = false,
        isPinned = pinned,
        recurrence = if (recurring) {
            RecurrenceSpec.Interval(
                base = RecurrenceSpec.RecurrenceBase.FROM_DUE,
                amount = 1,
                unit = DateTimeUnit.DAY,
            )
        } else {
            null
        },
        dependsOn = dependsOn.map { TaskId(it) }.toSet(),
        dueDate = dueDate,
        tags = emptyList(),
    )

    // ─── computeAgendaBadge ──────────────────────────────────────────────

    @Test
    fun `Blocked badge when task has incomplete dependency`() {
        val dep = t("dep", completed = false)
        val task = t("t", dependsOn = listOf("dep"))
        val all = listOf(dep, task)
        assertEquals(
            com.singularity.todo.feature.agenda.domain.model.AgendaBadge.Blocked,
            computeAgendaBadge(task, today, all),
        )
    }

    @Test
    fun `Blocked does NOT fire when dependency is completed`() {
        val dep = t("dep", completed = true)
        val task = t("t", dependsOn = listOf("dep"))
        val all = listOf(dep, task)
        // dep.isCompleted → isBlocked=false → priority falls through
        // (not pinned, not recurring, not completed) → dueDate == null → NoDate
        assertEquals(
            com.singularity.todo.feature.agenda.domain.model.AgendaBadge.NoDate,
            computeAgendaBadge(task, today, all),
        )
    }

    @Test
    fun `Pinned badge when task is pinned`() {
        val task = t("t", pinned = true)
        assertEquals(
            com.singularity.todo.feature.agenda.domain.model.AgendaBadge.Pinned,
            computeAgendaBadge(task, today, emptyList()),
        )
    }

    @Test
    fun `Recurring badge when task has recurrence`() {
        val task = t("t", recurring = true)
        assertEquals(
            com.singularity.todo.feature.agenda.domain.model.AgendaBadge.Recurring,
            computeAgendaBadge(task, today, emptyList()),
        )
    }

    @Test
    fun `Completed badge when task is completed`() {
        val task = t("t", completed = true)
        assertEquals(
            com.singularity.todo.feature.agenda.domain.model.AgendaBadge.Completed,
            computeAgendaBadge(task, today, emptyList()),
        )
    }

    @Test
    fun `Overdue badge when past-dated and not completed`() {
        val task = t("t", dueDate = today.minus(1, DateTimeUnit.DAY))
        assertEquals(
            com.singularity.todo.feature.agenda.domain.model.AgendaBadge.Overdue,
            computeAgendaBadge(task, today, emptyList()),
        )
    }

    @Test
    fun `NoDate badge when dueDate is null`() {
        val task = t("t", dueDate = null)
        assertEquals(
            com.singularity.todo.feature.agenda.domain.model.AgendaBadge.NoDate,
            computeAgendaBadge(task, today, emptyList()),
        )
    }

    @Test
    fun `null badge for normal in-date active task`() {
        val task = t("t", dueDate = today.plus(1, DateTimeUnit.DAY))
        assertNull(computeAgendaBadge(task, today, emptyList()))
    }

    @Test
    fun `priority Blocked over Pinned`() {
        val dep = t("dep")
        val pinnedBlocked = t("t", pinned = true, dependsOn = listOf("dep"))
        assertEquals(
            com.singularity.todo.feature.agenda.domain.model.AgendaBadge.Blocked,
            computeAgendaBadge(pinnedBlocked, today, listOf(dep, pinnedBlocked)),
        )
    }

    @Test
    fun `priority Completed over Overdue`() {
        // A completed past-due task should show Completed, not Overdue
        val task = t("t", dueDate = today.minus(1, DateTimeUnit.DAY), completed = true)
        assertEquals(
            com.singularity.todo.feature.agenda.domain.model.AgendaBadge.Completed,
            computeAgendaBadge(task, today, emptyList()),
        )
    }

    @Test
    fun `priority Pinned over Recurring`() {
        val task = t("t", pinned = true, recurring = true)
        assertEquals(
            com.singularity.todo.feature.agenda.domain.model.AgendaBadge.Pinned,
            computeAgendaBadge(task, today, emptyList()),
        )
    }

    // ─── isBlocked ─────────────────────────────────────────────────────

    @Test
    fun `isBlocked true when dependsOn contains incomplete task`() {
        val dep = t("dep", completed = false)
        val task = t("t", dependsOn = listOf("dep"))
        assertTrue(isBlocked(task, listOf(dep, task)))
    }

    @Test
    fun `isBlocked false when all dependsOn are completed`() {
        val dep = t("dep", completed = true)
        val task = t("t", dependsOn = listOf("dep"))
        assertFalse(isBlocked(task, listOf(dep, task)))
    }

    @Test
    fun `isBlocked false when dependsOn is empty`() {
        val task = t("t", dependsOn = emptyList())
        assertFalse(isBlocked(task, listOf(task)))
    }

    @Test
    fun `isBlocked false when dependency does not exist in allTasks`() {
        val task = t("t", dependsOn = listOf("non-existent"))
        assertFalse(isBlocked(task, listOf(task)))
    }

    @Test
    fun `isBlocked considers archived tasks as blockers released`() {
        // Archived (trashed) dependency must not block — its blocker is released
        val archivedDep = t("dep", completed = false).copy(archivedAt = epoch0)
        val task = t("t", dependsOn = listOf("dep"))
        assertFalse(isBlocked(task, listOf(archivedDep, task)))
    }
}
