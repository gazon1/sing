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
    fun `a satisfied post-condition wins over a late error`() {
        assertEquals(
            OperationOutcome.COMPLETED,
            resolveOperationOutcome(postConditionSatisfied = true, errorSinceDispatch = "Save failed"),
        )
    }

    @Test
    fun `an unverified post-condition with a post-dispatch error fails the operation`() {
        assertEquals(
            OperationOutcome.FAILED,
            resolveOperationOutcome(postConditionSatisfied = false, errorSinceDispatch = "Save failed"),
        )
    }

    @Test
    fun `an unverified post-condition with no error stays pending`() {
        assertEquals(
            OperationOutcome.PENDING,
            resolveOperationOutcome(postConditionSatisfied = false, errorSinceDispatch = null),
        )
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
