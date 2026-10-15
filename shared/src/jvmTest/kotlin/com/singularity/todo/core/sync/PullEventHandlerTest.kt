package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.NoOpCrashReportingPort
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [PullEventHandler.handleEvent].
 *
 * Each test sends a single [SyncEvent] through [PullEventHandler.handleEvent] and
 * asserts the [ApplyOutcome].  The handler is a pure function of the event's fields
 * and the [applyRemote] lambda; no I/O is exercised.
 *
 * The `deleteDocument` parameter is passed as a simple lambda so the DELETED branch
 * can be exercised without constructing six repository stubs.
 *
 * ## What is covered
 *
 * | Branch | Trigger | Expected outcome |
 * |---|---|---|
 * | Protocol version guard | protocolVersion > CURRENT | Skipped |
 * | Null payload | data == null | Skipped |
 * | JsonNull payload | data == JsonNull | Skipped |
 * | Non-object payload | data is not JsonObject | Skipped |
 * | applyRemote throws SerializationException | — | Skipped |
 * | applyRemote throws other exception | — | Failed |
 * | DELETED → deleteDocument() → success | — | Applied |
 * | DELETED → deleteDocument() → failure | — | Failed |
 */
@Tag("fast")
class PullEventHandlerTest {

    private val log = Logger.withTag("PullEventHandlerTest")
    private val noOpCrashReporter = NoOpCrashReportingPort()

    // A real-looking writer (from fakeSyncDocumentWriter) satisfies the parameter;
    // delete is overridden via the deleteDocument lambda.
    private fun makeWriter() = fakeSyncDocumentWriter()

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun TestScope.handler(
        deleteDocument: suspend (DocType, String) -> Result<Unit> = { _, _ -> Result.success(Unit) },
        crashReporter: CrashReportingPort = noOpCrashReporter,
    ): PullEventHandler = PullEventHandler(
        writer = makeWriter(),
        log = log,
        crashReporter = crashReporter,
        deleteDocument = deleteDocument,
    )

    private fun payload(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    private fun event(
        eventType: SyncEventType,
        data: kotlinx.serialization.json.JsonElement? = payload("title" to "Hello"),
        protocolVersion: Int = SyncProtocol.CURRENT_PROTOCOL_VERSION,
    ) = SyncEvent(
        serverLsn = 1L,
        entityId = "entity-1",
        entityType = DocType.Task,
        eventType = eventType,
        data = data,
        createdAt = 0L,
        hlc = null,
        protocolVersion = protocolVersion,
    )

    private fun deletedEvent() = SyncEvent(
        serverLsn = 1L,
        entityId = "task-1",
        entityType = DocType.Task,
        eventType = SyncEventType.DELETED,
        data = null,
        createdAt = 0L,
        hlc = null,
        protocolVersion = SyncProtocol.CURRENT_PROTOCOL_VERSION,
    )

    // ─── Protocol version guard ─────────────────────────────────────────

    @Test
    fun `protocol version newer than current → Skipped`() = runTest {
        val h = handler()
        val outcome = h.handleEvent(
            event(SyncEventType.CREATED, protocolVersion = SyncProtocol.CURRENT_PROTOCOL_VERSION + 1),
        ) { }

        assertIs<ApplyOutcome.Skipped>(outcome)
        assertEquals(
            "protocol version ${SyncProtocol.CURRENT_PROTOCOL_VERSION + 1} is newer than ${SyncProtocol.CURRENT_PROTOCOL_VERSION}",
            outcome.reason,
        )
    }

    @Test
    fun `current protocol version → not skipped`() = runTest {
        val h = handler()
        val outcome = h.handleEvent(event(SyncEventType.CREATED)) { }

        assertIs<ApplyOutcome.Applied>(outcome)
    }

    // ─── Payload validation ───────────────────────────────────────────

    @Test
    fun `null data → Skipped`() = runTest {
        val h = handler()
        val e = SyncEvent(
            serverLsn = 1L, entityId = "e1", entityType = DocType.Task,
            eventType = SyncEventType.CREATED, data = null,
            createdAt = 0L, hlc = null,
            protocolVersion = SyncProtocol.CURRENT_PROTOCOL_VERSION,
        )
        val outcome = h.handleEvent(e) { }

        assertIs<ApplyOutcome.Skipped>(outcome)
        assertEquals("the event carries no document", outcome.reason)
    }

    @Test
    fun `JsonNull data → Skipped`() = runTest {
        val h = handler()
        val outcome = h.handleEvent(
            event(SyncEventType.CREATED, data = JsonNull),
        ) { }

        assertIs<ApplyOutcome.Skipped>(outcome)
        assertEquals("the event carries no document", outcome.reason)
    }

    @Test
    fun `non-object data → Skipped`() = runTest {
        val h = handler()
        val outcome = h.handleEvent(
            event(SyncEventType.CREATED, data = JsonPrimitive("string")),
        ) { }

        assertIs<ApplyOutcome.Skipped>(outcome)
        assertEquals("the event payload is not a document", outcome.reason)
    }

    // ─── applyRemote outcomes ──────────────────────────────────────────

    @Test
    fun `applyRemote SerializationException → Skipped`() = runTest {
        val h = handler()
        val ex = SerializationException("Unrecognisable format")
        val outcome = h.handleEvent(event(SyncEventType.CREATED)) { throw ex }

        assertIs<ApplyOutcome.Skipped>(outcome)
        assertEquals("the event payload does not decode: Unrecognisable format", outcome.reason)
    }

    @Test
    fun `applyRemote other exception → Failed with crash report`() = runTest {
        val crashReporter = CapturingCrashReporter()
        val h = handler(crashReporter = crashReporter)
        val ex = IllegalStateException("Disk full")
        var called = false

        val outcome = h.handleEvent(event(SyncEventType.CREATED)) {
            called = true
            throw ex
        }

        assertIs<ApplyOutcome.Failed>(outcome)
        assertEquals("the write did not happen: Disk full", outcome.reason)
        assertEquals(1, crashReporter.reported.size)
        assertEquals(ex, crashReporter.reported[0].first)
        assertEquals("sync.apply_failed", crashReporter.reported[0].second)
        assertTrue(called)
    }

    @Test
    fun `CancellationException escapes`() = runTest {
        val h = handler()
        assertThrows<kotlinx.coroutines.CancellationException> {
            h.handleEvent(event(SyncEventType.CREATED)) {
                throw kotlinx.coroutines.CancellationException()
            }
        }
    }

    // ─── DELETED branch ───────────────────────────────────────────────

    @Test
    fun `DELETED with successful delete → Applied`() = runTest {
        var deleted = false
        val h = handler(deleteDocument = { type: DocType, id: String ->
            deleted = true
            assertEquals(DocType.Task, type)
            assertEquals("task-1", id)
            Result.success(Unit)
        })

        val outcome = h.handleEvent(deletedEvent()) { }

        assertIs<ApplyOutcome.Applied>(outcome)
        assertTrue(deleted)
    }

    @Test
    fun `DELETED with failed delete → Failed`() = runTest {
        val ex = IllegalStateException("Row not found")
        val h = handler(deleteDocument = { _: DocType, _: String -> Result.failure(ex) })

        val outcome = h.handleEvent(deletedEvent()) { }

        assertIs<ApplyOutcome.Failed>(outcome)
        assertEquals("the delete did not happen: Row not found", outcome.reason)
    }
}

// ======================================================================
// Test double
// ======================================================================

private class CapturingCrashReporter : CrashReportingPort {
    val reported = mutableListOf<Pair<Throwable, String>>()

    override fun report(error: Throwable, issueKey: String) {
        reported += error to issueKey
    }

    override fun addBreadcrumb(message: String) {}
}
