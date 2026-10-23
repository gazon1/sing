@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.core.llm

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rules that fold an agent's events into what the reader sees.
 *
 * These are the rules three surfaces will share, so they are tested once, here, rather than
 * three times through three screens.
 */
@Tag("fast")
class AgentActivityTest {

    @Test
    fun aTextDeltaChangesNothing() {
        val state = AgentActivityState() + AgentEvent.TextDelta("hello")

        assertTrue(state.calls.isEmpty(), "The text belongs to the message, not to the activity")
        assertTrue(state.plan.isEmpty(), "And a delta is not a plan")
    }

    @Test
    fun theEndOfTheTurnChangesNothing() {
        val state = AgentActivityState() + AgentEvent.TurnCompleted

        assertTrue(state.calls.isEmpty(), "The completion event is not a tool call")
        assertTrue(state.plan.isEmpty(), "And it is not a plan")
    }

    @Test
    fun aToolCallIsAddedOnce() {
        val state = AgentActivityState() +
            AgentEvent.ToolCallUpdated("c1", "list_tasks", ToolCallStatus.RUNNING) +
            AgentEvent.ToolCallUpdated("c2", "get_task", ToolCallStatus.RUNNING)

        assertEquals(2, state.calls.size, "Two calls, two cards")
    }

    @Test
    fun aKnownToolCallIsUpdatedInPlace() {
        val state = AgentActivityState() +
            AgentEvent.ToolCallUpdated("c1", "list_tasks", ToolCallStatus.RUNNING) +
            AgentEvent.ToolCallUpdated("c1", "list_tasks", ToolCallStatus.COMPLETED, "3 tasks")

        assertEquals(1, state.calls.size, "The same call is one card, not two")
        assertEquals(ToolCallStatus.COMPLETED, state.calls.single().status, "And it shows the outcome")
        assertEquals("3 tasks", state.calls.single().summary, "And the summary that came with it")
    }

    @Test
    fun aPlanUpdateReplacesThePreviousPlan() {
        val state = AgentActivityState() +
            AgentEvent.PlanUpdated(
                listOf(
                    AgentPlanStep("read the notes", PlanStepStatus.PENDING),
                    AgentPlanStep("replan the week", PlanStepStatus.PENDING),
                ),
            ) +
            AgentEvent.PlanUpdated(
                listOf(AgentPlanStep("read the notes", PlanStepStatus.COMPLETED)),
            )

        assertEquals(1, state.plan.size, "A plan update is the plan as it is now")
        assertEquals(PlanStepStatus.COMPLETED, state.plan.single().status, "Not a patch to the old one")
    }

    @Test
    fun aFailedCallKeepsItsPlaceInTheList() {
        val state = AgentActivityState() +
            AgentEvent.ToolCallUpdated("c1", "a", ToolCallStatus.RUNNING) +
            AgentEvent.ToolCallUpdated("c2", "b", ToolCallStatus.RUNNING) +
            AgentEvent.ToolCallUpdated("c1", "a", ToolCallStatus.FAILED, "boom")

        assertEquals(listOf("c1", "c2"), state.calls.map { it.id }, "The order the calls were made")
        assertEquals(ToolCallStatus.FAILED, state.calls.first().status, "And the first call's outcome")
    }
}
