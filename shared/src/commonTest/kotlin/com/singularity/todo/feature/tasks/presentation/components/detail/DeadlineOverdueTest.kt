package com.singularity.todo.feature.tasks.presentation.components.detail

import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule that decides whether a deadline is overdue.
 *
 * The row used to ask only "is the deadline before today", and a task that was
 * already done kept saying «Просрочен» — the app reported the task as late
 * for work it had finished.
 */
@Tag("fast")
class DeadlineOverdueTest {
    private val today = LocalDate(2026, 10, 1)
    private val yesterday = LocalDate(2026, 9, 30)
    private val tomorrow = LocalDate(2026, 10, 2)

    @Test
    fun `a deadline before today on an open task is overdue`() {
        assertTrue(isDeadlineOverdue(yesterday, today, isCompleted = false))
    }

    @Test
    fun `a deadline today is not overdue`() {
        assertFalse(isDeadlineOverdue(today, today, isCompleted = false))
    }

    @Test
    fun `a deadline after today is not overdue`() {
        assertFalse(isDeadlineOverdue(tomorrow, today, isCompleted = false))
    }

    @Test
    fun `a task without a deadline is not overdue`() {
        assertFalse(isDeadlineOverdue(null, today, isCompleted = false))
    }

    @Test
    fun `a completed task is never overdue`() {
        assertFalse(
            isDeadlineOverdue(yesterday, today, isCompleted = true),
            "the work is finished — the deadline cannot be missed",
        )
    }
}
