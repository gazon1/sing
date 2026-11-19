@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.mcp.introspection

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("fast")
class OperationOutcomeTest {
    @Test
    fun `a satisfied post-condition wins over a later failure or deadline`() {
        assertEquals(
            OperationOutcome.COMPLETED,
            resolveOperationOutcome(postConditionSatisfied = true, failureObserved = true, pastDeadline = true),
        )
    }

    @Test
    fun `an observed failure ends the operation as failed`() {
        assertEquals(
            OperationOutcome.FAILED,
            resolveOperationOutcome(postConditionSatisfied = false, failureObserved = true, pastDeadline = false),
        )
    }

    @Test
    fun `the observation deadline makes an unresolved operation terminal`() {
        assertEquals(
            OperationOutcome.FAILED,
            resolveOperationOutcome(postConditionSatisfied = false, failureObserved = false, pastDeadline = true),
        )
    }

    @Test
    fun `no signal before the deadline stays pending`() {
        assertEquals(
            OperationOutcome.PENDING,
            resolveOperationOutcome(postConditionSatisfied = false, failureObserved = false, pastDeadline = false),
        )
    }

    @Test
    fun `a failure is attributed only to messages its own action emits`() {
        assertTrue("Save failed" in actionFailureMessages("set_priority"))
        assertTrue("Delete failed" in actionFailureMessages("delete_task"))
        assertTrue("Archive failed" in actionFailureMessages("delete_task"))
        assertTrue("Restore failed" in actionFailureMessages("restore_task"))
        assertTrue("Add failed" in actionFailureMessages("add_checklist_item"))

        // Unrelated coordinator errors are never attributed to an action.
        assertFalse("Save failed" in actionFailureMessages("delete_task"))
        assertFalse("Not found" in actionFailureMessages("set_priority"))
        assertFalse("Error" in actionFailureMessages("toggle_complete"))
        assertTrue(actionFailureMessages("custom-action").isEmpty())
    }

    @Test
    fun `a failed operation is terminal and no longer blocks the session`() {
        val tracker = OperationTracker()
        val op = tracker.create("set_priority")
        assertTrue(tracker.hasInFlight())

        tracker.complete(op, OperationRecord(op.id, op.action, OperationStatus.FAILED, message = "Save failed"))

        assertTrue(op.isDone())
        assertFalse(tracker.hasInFlight())
    }
}
