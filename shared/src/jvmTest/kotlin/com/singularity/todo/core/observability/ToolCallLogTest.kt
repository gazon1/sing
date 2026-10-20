package com.singularity.todo.core.observability

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The recorder: what it writes, and what it refuses to let happen.
 *
 * Two properties matter more than the rest:
 *
 * * **A write failure never changes the outcome of the call.** An observation that
 *   breaks the observed is worse than no observation — the tool would fail for a
 *   reason that has nothing to do with the tool.
 * * **A record carries no payload.** Not the arguments, not the result, not the text of
 *   an exception. A record outlives the session it describes, and an exception message
 *   can carry anything.
 */
@Tag("fast")
class ToolCallLogTest {

    private class RecordingLog : ToolCallLog {
        val events = mutableListOf<ToolCallEvent>()
        override fun record(event: ToolCallEvent) {
            events += event
        }
    }

    private class FailingLog : ToolCallLog {
        override fun record(event: ToolCallEvent) = throw IllegalStateException("the disk is full")
    }

    private fun recorder(log: ToolCallLog) = ToolCallRecorder(
        log = log,
        nowEpochMillis = { 1_700_000_000_000L },
    )

    @Test
    fun `a successful call is recorded once`() = runTest {
        val log = RecordingLog()

        val result = recorder(log).timed("create_task", ToolCallSource.MCP) { "done" }

        assertEquals("done", result)
        assertEquals(1, log.events.size)
        val event = log.events.single()
        assertEquals("create_task", event.tool)
        assertEquals("MCP", event.source)
        assertEquals("OK", event.outcome)
        assertEquals(null, event.errorCode)
        assertEquals(1_700_000_000_000L, event.ts)
    }

    @Test
    fun `a business failure is recorded as a business error`() = runTest {
        val log = RecordingLog()

        val thrown = runCatching {
            recorder(log).timed(
                tool = "create_task",
                source = ToolCallSource.MCP,
                classify = { ToolCallError("validation", business = true) },
            ) { throw IllegalArgumentException("title must not be blank") }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException, "the failure must reach the caller")
        val event = log.events.single()
        assertEquals("BUSINESS_ERROR", event.outcome)
        assertEquals("validation", event.errorCode)
    }

    @Test
    fun `an unclassified failure is recorded as an internal error`() = runTest {
        val log = RecordingLog()

        runCatching {
            recorder(log).timed("create_task", ToolCallSource.MCP) { throw IllegalStateException("boom") }
        }

        val event = log.events.single()
        assertEquals("INTERNAL_ERROR", event.outcome)
        assertEquals("internal", event.errorCode)
    }

    @Test
    fun `a write failure does not change the outcome of the call`() = runTest {
        val recorder = recorder(FailingLog())

        val result = recorder.timed("create_task", ToolCallSource.MCP) { "done" }

        assertEquals("done", result, "the call must succeed even when the record cannot be written")
        assertEquals(1, recorder.droppedRecords, "the lost record must be counted")
    }

    @Test
    fun `a write failure never replaces the failure of the call`() = runTest {
        val recorder = recorder(FailingLog())

        val thrown = runCatching {
            recorder.timed("create_task", ToolCallSource.MCP) { throw IllegalStateException("boom") }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException, "the original failure must reach the caller")
        assertEquals(1, recorder.droppedRecords)
    }

    @Test
    fun `the record carries the session and the client, and nothing else`() = runTest {
        val log = RecordingLog()

        recorder(log).timed(
            tool = "create_task",
            source = ToolCallSource.IN_APP,
            context = ToolCallContext(sessionId = "session-1", client = "claude-code@1.2.3"),
        ) { "done" }

        val event = log.events.single()
        assertEquals("session-1", event.sessionId)
        assertEquals("claude-code@1.2.3", event.client)
        assertEquals("IN_APP", event.source)
    }

    @Test
    fun `the event has no field that could carry a payload`() {
        // The absence is the property. A field that does not exist cannot leak.
        val instanceFields = ToolCallEvent::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .filterNot { it == "Companion" }

        assertTrue(
            instanceFields.containsAll(
                listOf("schemaVersion", "event", "ts", "source", "tool", "outcome", "errorCode"),
            ),
            "the record must keep the fields it reports with, got $instanceFields",
        )

        val payloadBearing = instanceFields.filter { field ->
            listOf("arg", "payload", "result", "body", "text", "message", "value")
                .any { needle -> field.contains(needle, ignoreCase = true) }
        }
        assertEquals(
            emptyList(),
            payloadBearing,
            "the record must not grow a field that could carry arguments or results",
        )
    }
}
