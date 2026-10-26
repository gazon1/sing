package com.singularity.todo.feature.ai.acp

import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.AgentPlanStep
import com.singularity.todo.core.llm.AgentTurnOutcome
import com.singularity.todo.core.llm.PlanStepStatus
import com.singularity.todo.core.llm.ToolCallStatus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mapping from what the agent did to the application's activity.
 *
 * ## Why this test is exhaustive on purpose
 *
 * The protocol grows. Every new session update arrives at the application's
 * vocabulary, and the adapter has to decide what it means. A decision made by
 * accident — an update that falls into a branch nobody wrote — shows the reader
 * something no tool call justified.
 *
 * So the ignored variants are enumerated one by one, and each one asserts that it
 * produces **nothing**. A test that only checked the interesting variants would pass
 * just as well if the ignore list quietly grew a hole.
 *
 * And the stop reasons are checked one by one, because the difference between "the
 * agent answered" and "the agent refused" is the difference between a good turn and a
 * broken one.
 *
 * ## Why no protocol type appears here
 *
 * The protocol library is reached in one file, and this is not it. The table below is
 * the application's own, so it can be checked without the library — and the library's
 * own translation into this vocabulary is checked in the test that does use it.
 */
@Tag("fast")
class AcpEventAdapterTest {

    // ── Text ───────────────────────────────────────────────────────────────────

    @Test
    fun `an agent message chunk becomes a text delta`() {
        val event = AcpEventAdapter.adapt(AcpSessionUpdate.TextDelta("Hello"))

        assertEquals(AgentEvent.TextDelta("Hello"), event)
    }

    // ── Tool calls ─────────────────────────────────────────────────────────────

    @Test
    fun `a new tool call becomes a running tool call`() {
        val event = AcpEventAdapter.adapt(
            AcpSessionUpdate.ToolCall(
                id = "call-1",
                name = "Read file",
                status = AcpToolCallState.PENDING,
                summary = "Read file",
            ),
        )

        assertEquals(
            AgentEvent.ToolCallUpdated(
                id = "call-1",
                name = "Read file",
                status = ToolCallStatus.RUNNING,
                summary = "Read file",
            ),
            event,
        )
    }

    @Test
    fun `a pending call and an in-progress call both read as running`() {
        val pending = AcpEventAdapter.adapt(
            AcpSessionUpdate.ToolCall("call-1", "Read file", AcpToolCallState.PENDING, null),
        )
        val inProgress = AcpEventAdapter.adapt(
            AcpSessionUpdate.ToolCall("call-1", "Read file", AcpToolCallState.IN_PROGRESS, null),
        )

        assertEquals(ToolCallStatus.RUNNING, (pending as AgentEvent.ToolCallUpdated).status)
        assertEquals(ToolCallStatus.RUNNING, (inProgress as AgentEvent.ToolCallUpdated).status)
    }

    @Test
    fun `a completed call reads as completed and a failed one as failed`() {
        val completed = AcpEventAdapter.adapt(
            AcpSessionUpdate.ToolCall("call-1", "Read file", AcpToolCallState.COMPLETED, null),
        )
        val failed = AcpEventAdapter.adapt(
            AcpSessionUpdate.ToolCall("call-1", "Read file", AcpToolCallState.FAILED, null),
        )

        assertEquals(ToolCallStatus.COMPLETED, (completed as AgentEvent.ToolCallUpdated).status)
        assertEquals(ToolCallStatus.FAILED, (failed as AgentEvent.ToolCallUpdated).status)
    }

    @Test
    fun `a call without a status reads as running`() {
        val event = AcpEventAdapter.adapt(
            AcpSessionUpdate.ToolCall("call-1", "Read file", AcpToolCallState.PENDING, null),
        )

        assertEquals(
            ToolCallStatus.RUNNING,
            (event as AgentEvent.ToolCallUpdated).status,
            "a call with no status yet has not finished",
        )
    }

    // ── The plan ───────────────────────────────────────────────────────────────

    @Test
    fun `a plan update becomes a plan snapshot`() {
        val event = AcpEventAdapter.adapt(
            AcpSessionUpdate.Plan(
                steps = listOf(
                    AcpPlanStep("Read the inbox", AcpPlanStepState.IN_PROGRESS),
                    AcpPlanStep("Summarise", AcpPlanStepState.PENDING),
                    AcpPlanStep("Done", AcpPlanStepState.COMPLETED),
                ),
            ),
        )

        assertEquals(
            AgentEvent.PlanUpdated(
                steps = listOf(
                    AgentPlanStep("Read the inbox", PlanStepStatus.IN_PROGRESS),
                    AgentPlanStep("Summarise", PlanStepStatus.PENDING),
                    AgentPlanStep("Done", PlanStepStatus.COMPLETED),
                ),
            ),
            event,
        )
    }

    // ── The ignore list ────────────────────────────────────────────────────────

    @Test
    fun `every ignored session update produces no event`() {
        val ignored = listOf(
            "the user's own message" to AcpSessionUpdate.UserMessage,
            "the agent's thoughts" to AcpSessionUpdate.AgentThought,
            "a session setting" to AcpSessionUpdate.SessionSetting,
            "an unknown update" to AcpSessionUpdate.Unrecognised,
        )

        ignored.forEach { (what, update) ->
            assertNull(
                AcpEventAdapter.adapt(update),
                "$what must not produce an event: it is not something the reader asked to see",
            )
        }
    }

    // ── The stop reasons ───────────────────────────────────────────────────────

    @Test
    fun `the stop reasons collapse to the right outcomes`() {
        assertEquals(
            AgentTurnOutcome.Completed(),
            AcpEventAdapter.outcomeFor(AcpStopReason.END_TURN),
            "the agent finished its answer",
        )

        val truncated = AcpEventAdapter.outcomeFor(AcpStopReason.MAX_TOKENS)
        assertTrue(truncated is AgentTurnOutcome.Completed, "a truncated answer is still an answer")
        assertTrue(
            truncated.note != null,
            "and the reader is told why it stopped",
        )

        val turnLimited = AcpEventAdapter.outcomeFor(AcpStopReason.MAX_TURN_REQUESTS)
        assertTrue(turnLimited is AgentTurnOutcome.Completed, "a turn-limited answer is still an answer")
        assertTrue(turnLimited.note != null, "and the reader is told why")

        assertEquals(
            AgentTurnOutcome.Failed("The agent refused to answer."),
            AcpEventAdapter.outcomeFor(AcpStopReason.REFUSAL),
            "a refusal is not an answer, so it is a failure",
        )

        assertEquals(
            AgentTurnOutcome.Cancelled,
            AcpEventAdapter.outcomeFor(AcpStopReason.CANCELLED),
            "a cancelled turn is cancelled",
        )
    }
}
