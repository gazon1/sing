package com.singularity.todo.feature.ai.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus as AcpToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.singularity.todo.core.llm.AgentEvent
import com.singularity.todo.core.llm.AgentPlanStep
import com.singularity.todo.core.llm.AgentTurnOutcome
import com.singularity.todo.core.llm.PlanStepStatus
import com.singularity.todo.core.llm.ToolCallStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mapping from the protocol's session updates to the application's activity.
 *
 * ## Why this test is exhaustive on purpose
 *
 * The protocol grows. Every new session update arrives at the adapter, and the adapter
 * has to decide what it means. A decision made by accident — an update that falls into
 * a branch nobody wrote — shows the reader something no tool call justified.
 *
 * So the ignored variants are enumerated one by one, and each one asserts that it
 * produces **nothing**. A test that only checked the interesting variants would pass
 * just as well if the ignore list quietly grew a hole.
 *
 * And the stop reasons are checked one by one, because the difference between "the
 * agent answered" and "the agent refused" is the difference between a good turn and a
 * broken one.
 */
@Tag("fast")
class AcpEventAdapterTest {

    // ── Text ───────────────────────────────────────────────────────────────────

    @Test
    fun `an agent message chunk becomes a text delta`() {
        val event = AcpEventAdapter.adapt(
            SessionUpdate.AgentMessageChunk(content = ContentBlock.Text("Hello")),
        )

        assertEquals(AgentEvent.TextDelta("Hello"), event)
    }

    @Test
    fun `a non-text chunk produces nothing`() {
        val event = AcpEventAdapter.adapt(
            SessionUpdate.AgentMessageChunk(content = ContentBlock.Image(data = "aGk=", mimeType = "image/png")),
        )

        assertNull(event, "an image in a message is not a piece of the answer")
    }

    // ── Tool calls ─────────────────────────────────────────────────────────────

    @Test
    fun `a new tool call becomes a running tool call`() {
        val event = AcpEventAdapter.adapt(
            SessionUpdate.ToolCall(
                toolCallId = ToolCallId("call-1"),
                title = "Read file",
                kind = ToolKind.READ,
                status = AcpToolCallStatus.PENDING,
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
            SessionUpdate.ToolCallUpdate(toolCallId = ToolCallId("call-1"), status = AcpToolCallStatus.PENDING),
        )
        val inProgress = AcpEventAdapter.adapt(
            SessionUpdate.ToolCallUpdate(toolCallId = ToolCallId("call-1"), status = AcpToolCallStatus.IN_PROGRESS),
        )

        assertEquals(ToolCallStatus.RUNNING, (pending as AgentEvent.ToolCallUpdated).status)
        assertEquals(ToolCallStatus.RUNNING, (inProgress as AgentEvent.ToolCallUpdated).status)
    }

    @Test
    fun `a completed call reads as completed and a failed one as failed`() {
        val completed = AcpEventAdapter.adapt(
            SessionUpdate.ToolCallUpdate(toolCallId = ToolCallId("call-1"), status = AcpToolCallStatus.COMPLETED),
        )
        val failed = AcpEventAdapter.adapt(
            SessionUpdate.ToolCallUpdate(toolCallId = ToolCallId("call-1"), status = AcpToolCallStatus.FAILED),
        )

        assertEquals(ToolCallStatus.COMPLETED, (completed as AgentEvent.ToolCallUpdated).status)
        assertEquals(ToolCallStatus.FAILED, (failed as AgentEvent.ToolCallUpdated).status)
    }

    @Test
    fun `a call without a status reads as running`() {
        val event = AcpEventAdapter.adapt(
            SessionUpdate.ToolCallUpdate(toolCallId = ToolCallId("call-1")),
        )

        assertEquals(
            ToolCallStatus.RUNNING,
            (event as AgentEvent.ToolCallUpdated).status,
            "a call with no status yet has not finished",
        )
    }

    @Test
    fun `the summary names the fields and does not quote a long value`() {
        val event = AcpEventAdapter.adapt(
            SessionUpdate.ToolCall(
                toolCallId = ToolCallId("call-1"),
                title = "Create task",
                rawInput = JsonObject(
                    mapOf(
                        "title" to JsonPrimitive("Buy groceries"),
                        "description" to JsonPrimitive("x".repeat(200)),
                    ),
                ),
            ),
        )

        val summary = (event as AgentEvent.ToolCallUpdated).summary
        assertTrue(summary != null, "the call carries a summary")
        assertTrue(summary.contains("title=Buy groceries"), "the summary names the field and its short value: $summary")
        assertTrue(summary.contains("description=…"), "a long value is replaced by an ellipsis: $summary")
        assertTrue(summary.length <= 200, "the summary stays a label: ${summary.length}")
    }

    // ── The plan ───────────────────────────────────────────────────────────────

    @Test
    fun `a plan update becomes a plan snapshot`() {
        val event = AcpEventAdapter.adapt(
            SessionUpdate.PlanUpdate(
                entries = listOf(
                    PlanEntry("Read the inbox", PlanEntryPriority.HIGH, PlanEntryStatus.IN_PROGRESS),
                    PlanEntry("Summarise", PlanEntryPriority.LOW, PlanEntryStatus.PENDING),
                    PlanEntry("Done", PlanEntryPriority.MEDIUM, PlanEntryStatus.COMPLETED),
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
            "the user's own message" to SessionUpdate.UserMessageChunk(content = ContentBlock.Text("hello")),
            "the agent's thoughts" to SessionUpdate.AgentThoughtChunk(content = ContentBlock.Text("thinking")),
            "available commands" to SessionUpdate.AvailableCommandsUpdate(emptyList()),
            "the current mode" to SessionUpdate.CurrentModeUpdate(
                currentModeId = com.agentclientprotocol.model.SessionModeId("m"),
            ),
            "an unknown update" to SessionUpdate.UnknownSessionUpdate(
                sessionUpdateType = "something_new",
                rawJson = JsonObject(emptyMap()),
            ),
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
            AcpEventAdapter.outcomeFor(StopReason.END_TURN),
            "the agent finished its answer",
        )

        val truncated = AcpEventAdapter.outcomeFor(StopReason.MAX_TOKENS)
        assertTrue(truncated is AgentTurnOutcome.Completed, "a truncated answer is still an answer")
        assertTrue(
            (truncated as AgentTurnOutcome.Completed).note != null,
            "and the reader is told why it stopped",
        )

        val turnLimited = AcpEventAdapter.outcomeFor(StopReason.MAX_TURN_REQUESTS)
        assertTrue(turnLimited is AgentTurnOutcome.Completed, "a turn-limited answer is still an answer")
        assertTrue((turnLimited as AgentTurnOutcome.Completed).note != null, "and the reader is told why")

        assertEquals(
            AgentTurnOutcome.Failed("The agent refused to answer."),
            AcpEventAdapter.outcomeFor(StopReason.REFUSAL),
            "a refusal is not an answer, so it is a failure",
        )

        assertEquals(
            AgentTurnOutcome.Cancelled,
            AcpEventAdapter.outcomeFor(StopReason.CANCELLED),
            "a cancelled turn is cancelled",
        )
    }
}
