package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.logic.Blocking.isBlocked
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskMarking
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The markings a task carries, and the rules that decide them.
 *
 * This replaces the old single-badge policy test
 * (`AgendaBadgePolicyTest` in the agenda feature): the row used to carry one
 * badge and the first matching rule won, so a task that was both blocked and
 * overdue looked like only one of them. The markings are a **set** now, and
 * every fact that applies is stated.
 */
@Tag("fast")
class TaskMarkingPolicyTest {

    private val today = LocalDate(2026, 10, 14)
    private val epoch0 = Instant.fromEpochMilliseconds(0)

    private fun t(
        id: String,
        dueDate: LocalDate? = null,
        completed: Boolean = false,
        pinned: Boolean = false,
        recurring: Boolean = false,
        blockedBy: List<String> = emptyList(),
        followsUp: List<String> = emptyList(),
        workflowState: TaskWorkflowState = TaskWorkflowState.Open,
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
        blockedBy = blockedBy.map { TaskId(it) }.toSet(),
        followsUp = followsUp.map { TaskId(it) }.toSet(),
        workflowState = workflowState,
        dueDate = dueDate,
        tags = emptyList(),
    )

    // ─── The stored workflow state ──────────────────────────────────────

    @Test
    fun `a task in progress carries the in-progress marking`() {
        val task = t("t", workflowState = TaskWorkflowState.InProgress)

        assertTrue(
            computeTaskMarkings(task, today).contains(TaskMarking.InProgress),
            "the state the user set is the marking they look for",
        )
    }

    @Test
    fun `a task marked next-up carries the next-up marking`() {
        val task = t("t", workflowState = TaskWorkflowState.NextUp)

        assertTrue(
            computeTaskMarkings(task, today).contains(TaskMarking.NextUp),
            "the state the user set is the marking they look for",
        )
    }

    @Test
    fun `an open task carries neither of the stored-state markings`() {
        val task = t("t", workflowState = TaskWorkflowState.Open)

        val markings = computeTaskMarkings(task, today)
        assertFalse(markings.contains(TaskMarking.InProgress))
        assertFalse(markings.contains(TaskMarking.NextUp))
    }

    // ─── The scheduling facts ───────────────────────────────────────────

    @Test
    fun `a past-due task carries the overdue marking`() {
        val task = t("t", dueDate = today.minus(1, DateTimeUnit.DAY))

        assertTrue(computeTaskMarkings(task, today).contains(TaskMarking.Overdue))
    }

    @Test
    fun `an undated task carries the no-date marking`() {
        val task = t("t", dueDate = null)

        assertTrue(computeTaskMarkings(task, today).contains(TaskMarking.NoDate))
    }

    @Test
    fun `a completed task carries the completed marking and not the overdue one`() {
        val task = t("t", dueDate = today.minus(1, DateTimeUnit.DAY), completed = true)

        val markings = computeTaskMarkings(task, today)
        assertTrue(markings.contains(TaskMarking.Completed))
        assertFalse(
            markings.contains(TaskMarking.Overdue),
            "a finished task is not overdue; the strike-through says it",
        )
    }

    @Test
    fun `a pinned recurring task carries both markings`() {
        val task = t("t", pinned = true, recurring = true)

        val markings = computeTaskMarkings(task, today)
        assertTrue(markings.contains(TaskMarking.Pinned))
        assertTrue(markings.contains(TaskMarking.Recurring))
    }

    // ─── The set, not the first match ───────────────────────────────────

    @Test
    fun `a task that is both blocked and overdue carries both markings`() {
        val task = t("t", dueDate = today.minus(1, DateTimeUnit.DAY))

        val markings = computeTaskMarkings(task, today, isBlocked = true)
        assertTrue(
            markings.contains(TaskMarking.Blocked),
            "the task waits for something unfinished",
        )
        assertTrue(
            markings.contains(TaskMarking.Overdue),
            "the old policy dropped the second fact; the set does not",
        )
    }

    @Test
    fun `a task that owns a follow-up edge carries the follow-up marking`() {
        val task = t("t", followsUp = listOf("other"))

        assertTrue(computeTaskMarkings(task, today).contains(TaskMarking.FollowUp))
    }

    @Test
    fun `a task that follows up is never marked blocked by that edge`() {
        val task = t("t", followsUp = listOf("other"))

        val markings = computeTaskMarkings(task, today)
        assertFalse(
            markings.contains(TaskMarking.Blocked),
            "a follow-up is not a wait; the two are mutually exclusive by construction",
        )
    }

    @Test
    fun `an ordinary task carries no marking other than the absence of a date`() {
        val task = t("t", dueDate = today.plus(1, DateTimeUnit.DAY))

        assertEquals(emptySet(), computeTaskMarkings(task, today))
    }

    // ─── The blocking fact itself ───────────────────────────────────────

    @Test
    fun `isBlocked true when blockedBy contains incomplete task`() {
        val dep = t("dep", completed = false)
        val task = t("t", blockedBy = listOf("dep"))

        assertTrue(isBlocked(task, listOf(dep, task)))
    }

    @Test
    fun `isBlocked false when all blockedBy are completed`() {
        val dep = t("dep", completed = true)
        val task = t("t", blockedBy = listOf("dep"))

        assertFalse(isBlocked(task, listOf(dep, task)))
    }

    @Test
    fun `isBlocked false when blockedBy is empty`() {
        val task = t("t", blockedBy = emptyList())

        assertFalse(isBlocked(task, listOf(task)))
    }

    @Test
    fun `isBlocked false when dependency does not exist in allTasks`() {
        val task = t("t", blockedBy = listOf("non-existent"))

        assertFalse(isBlocked(task, listOf(task)))
    }

    @Test
    fun `isBlocked considers archived tasks as blockers released`() {
        // Archived (trashed) dependency must not block — its blocker is released
        val archivedDep = t("dep", completed = false).copy(archivedAt = epoch0)
        val task = t("t", blockedBy = listOf("dep"))

        assertFalse(isBlocked(task, listOf(archivedDep, task)))
    }
}
