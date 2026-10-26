@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.singularity.todo.feature.ai.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionId
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.PlanEntryPriority
import com.agentclientprotocol.model.PlanEntryStatus
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus as AcpToolCallStatus
import com.agentclientprotocol.model.ToolKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The one place the protocol library is spoken.
 *
 * ## What this test is for
 *
 * The library is reached in a single file, and this test is the other half of that
 * promise: the translation out of the protocol's vocabulary is checked here, because
 * this is the only place where the protocol's shape is known.
 *
 * Everything above the door speaks the application's vocabulary and is checked
 * without the library — which is the point of the door. An upgrade of the library
 * changes this file and this test, and nothing else: a reader cannot see the
 * difference, because the application's vocabulary did not change.
 *
 * ## Why the ignored variants are enumerated
 *
 * The translation is exhaustive over the protocol's variants, and every variant that
 * the reader does not need has to become the application's "nothing" rather than
 * something that looks like activity.
 */
@Tag("fast")
class AcpSdkBoundaryTest {

    // ── What the protocol says becomes what the application says ───────────────

    @Test
    fun `a text chunk becomes a text delta`() {
        val update = SessionUpdate.AgentMessageChunk(content = ContentBlock.Text("Hello"))

        assertEquals(AcpSessionUpdate.TextDelta("Hello"), update.toDomain())
    }

    @Test
    fun `a chunk that is not text becomes nothing the reader can see`() {
        val update = SessionUpdate.AgentMessageChunk(
            content = ContentBlock.Image(data = "aGk=", mimeType = "image/png"),
        )

        assertEquals(AcpSessionUpdate.Unrecognised, update.toDomain())
    }

    @Test
    fun `a tool call becomes a tool call with a bounded summary`() {
        val update = SessionUpdate.ToolCall(
            toolCallId = ToolCallId("call-1"),
            title = "Create task",
            kind = ToolKind.EDIT,
            status = AcpToolCallStatus.PENDING,
            rawInput = JsonObject(
                mapOf(
                    "title" to JsonPrimitive("Buy groceries"),
                    "description" to JsonPrimitive("x".repeat(200)),
                ),
            ),
        )

        val domain = update.toDomain() as AcpSessionUpdate.ToolCall
        assertEquals("call-1", domain.id)
        assertEquals("Create task", domain.name)
        assertEquals(AcpToolCallState.PENDING, domain.status)

        val summary = domain.summary
        assertTrue(summary != null, "the call carries a summary")
        assertTrue(summary.contains("title=Buy groceries"), "the summary names the field: $summary")
        assertTrue(summary.contains("description=…"), "a long value is replaced by an ellipsis: $summary")
        assertTrue(summary.length <= 200, "the summary stays a label: ${summary.length}")
    }

    @Test
    fun `a plan becomes a snapshot of the plan`() {
        val update = SessionUpdate.PlanUpdate(
            entries = listOf(
                PlanEntry("Read the inbox", PlanEntryPriority.HIGH, PlanEntryStatus.IN_PROGRESS),
                PlanEntry("Done", PlanEntryPriority.MEDIUM, PlanEntryStatus.COMPLETED),
            ),
        )

        assertEquals(
            AcpSessionUpdate.Plan(
                steps = listOf(
                    AcpPlanStep("Read the inbox", AcpPlanStepState.IN_PROGRESS),
                    AcpPlanStep("Done", AcpPlanStepState.COMPLETED),
                ),
            ),
            update.toDomain(),
        )
    }

    @Test
    fun `every variant the reader does not need becomes the application's nothing`() {
        val ignored = listOf(
            "the user's own message" to SessionUpdate.UserMessageChunk(content = ContentBlock.Text("hello")),
            "the agent's thoughts" to SessionUpdate.AgentThoughtChunk(content = ContentBlock.Text("thinking")),
            "available commands" to SessionUpdate.AvailableCommandsUpdate(emptyList()),
            "an unknown update" to SessionUpdate.UnknownSessionUpdate(
                sessionUpdateType = "something_new",
                rawJson = JsonObject(emptyMap()),
            ),
        )

        ignored.forEach { (what, update) ->
            assertTrue(
                update.toDomain() !is AcpSessionUpdate.TextDelta,
                "$what must not become something the reader sees",
            )
        }
    }

    // ── How a permission is expressed on the wire ───────────────────────────────

    @Test
    fun `the option the reader chose is the option the agent is told about`() = runTest {
        val operations = SdkSessionOperations(
            ask = AcpSessionPermissions { PermissionDecision.Selected(ALLOW_OPTION_ID) },
            sessionEvents = AcpLifecycleSink { },
        )

        val response = operations.requestPermissions(
            toolCall = permissionToolCall(),
            permissions = permissionOptions(),
            _meta = null,
        )

        val outcome = assertIs<RequestPermissionOutcome.Selected>(response.outcome)
        assertEquals(ALLOW_OPTION_ID, outcome.optionId.value)
    }

    @Test
    fun `a refusal is the agent's own rejecting option`() = runTest {
        val operations = SdkSessionOperations(
            ask = AcpSessionPermissions { PermissionDecision.Declined },
            sessionEvents = AcpLifecycleSink { },
        )

        val response = operations.requestPermissions(
            toolCall = permissionToolCall(),
            permissions = permissionOptions(),
            _meta = null,
        )

        val outcome = assertIs<RequestPermissionOutcome.Selected>(response.outcome)
        assertEquals(REJECT_OPTION_ID, outcome.optionId.value)
    }

    @Test
    fun `a refusal with no rejecting option offered is a cancelled turn`() = runTest {
        val operations = SdkSessionOperations(
            ask = AcpSessionPermissions { PermissionDecision.Declined },
            sessionEvents = AcpLifecycleSink { },
        )

        val response = operations.requestPermissions(
            toolCall = permissionToolCall(),
            permissions = listOf(
                PermissionOption(
                    optionId = PermissionOptionId(ALLOW_OPTION_ID),
                    name = "Allow once",
                    kind = PermissionOptionKind.ALLOW_ONCE,
                ),
            ),
            _meta = null,
        )

        assertIs<RequestPermissionOutcome.Cancelled>(response.outcome)
    }

    @Test
    fun `the request that reaches the reader says what the agent wants`() = runTest {
        var seen: AcpPermissionRequest? = null
        val operations = SdkSessionOperations(
            ask = AcpSessionPermissions { request ->
                seen = request
                PermissionDecision.Declined
            },
            sessionEvents = AcpLifecycleSink { },
        )

        operations.requestPermissions(
            toolCall = permissionToolCall(),
            permissions = permissionOptions(),
            _meta = null,
        )

        val request = seen
        assertTrue(request != null, "the reader was asked")
        assertEquals("call-1", request.toolCallId)
        assertEquals("Read file", request.title)
        assertEquals(2, request.options.size)
        assertTrue(request.options.any { it.isRejection }, "the agent's refusing option is one of them")
    }
}

/** The tool call the fake agent asks permission for. */
private fun permissionToolCall() = SessionUpdate.ToolCallUpdate(
    toolCallId = ToolCallId("call-1"),
    title = "Read file",
)

/** The choices the fake agent offers: one that allows, one that refuses. */
private fun permissionOptions(): List<PermissionOption> = listOf(
    PermissionOption(
        optionId = PermissionOptionId(ALLOW_OPTION_ID),
        name = "Allow once",
        kind = PermissionOptionKind.ALLOW_ONCE,
    ),
    PermissionOption(
        optionId = PermissionOptionId(REJECT_OPTION_ID),
        name = "Reject",
        kind = PermissionOptionKind.REJECT_ONCE,
    ),
)

private const val ALLOW_OPTION_ID = "allow-once"
private const val REJECT_OPTION_ID = "reject-once"
