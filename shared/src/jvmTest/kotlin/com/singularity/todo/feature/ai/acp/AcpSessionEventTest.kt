package com.singularity.todo.feature.ai.acp

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.mutableLoggerConfigInit
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The lifecycle's own half of FR-044: the events reach the log.
 *
 * ## Why this is checked at all
 *
 * FR-044 asks for two things at once, and the two are opposites: the lifecycle
 * events MUST be written to the log, and they MUST NOT be written into the protocol
 * stream. The first half is checked here; the second half needs a real process and
 * lives in `AcpClientProcessTest`.
 *
 * ## Why the log is captured rather than the logger's global state
 *
 * A test that installs a log writer globally has to take it away again, and a test
 * that forgets leaves the next test reading lines it did not write. The logger is
 * therefore built with its own writer, and the assertion is about *that* logger —
 * no global state, no order dependence, nothing to clean up.
 */
@Tag("fast")
class AcpSessionEventTest {

    @Test
    fun `a lifecycle event reaches the log under its own tag`() {
        val log = CapturedLog()
        val sink = LoggingAcpLifecycle(log.logger())

        sink.record(
            AcpSessionEvent.Connected(agentName = "fake-agent", agentVersion = "1.0", protocolVersion = 1),
        )

        assertEquals(1, log.lines.size, "the event was written once")
        val line = log.lines.single()
        assertTrue(line.startsWith("acp-session:"), "the event is under its own tag: $line")
        assertTrue(line.contains("fake-agent"), "and it says which agent connected: $line")
        assertTrue(line.contains("1.0"), "and which version: $line")
    }

    @Test
    fun `every lifecycle event reads as one line that carries its facts`() {
        val events = listOf(
            AcpSessionEvent.Connected(agentName = "fake-agent", agentVersion = "1.0", protocolVersion = 1),
            AcpSessionEvent.SessionOpened(sessionId = "session-1"),
            AcpSessionEvent.PermissionRequested(toolCallId = "call-1", title = "Read file"),
            AcpSessionEvent.Disconnected(reason = "the agent stopped"),
            AcpSessionEvent.ProcessTreeTerminated(processes = 3),
        )

        events.forEach { event ->
            val line = event.describe()
            assertFalse(line.contains('\n'), "a log line is one line: $line")
            assertTrue(line.isNotBlank(), "and it says something: $event")
        }

        assertTrue(events[1].describe().contains("session-1"), "the session identity is in the line")
        assertTrue(events[2].describe().contains("call-1"), "the permission request names the call")
        assertTrue(events[2].describe().contains("Read file"), "and what the agent wanted")
        assertTrue(events[3].describe().contains("the agent stopped"), "the disconnection says why")
        assertTrue(events[4].describe().contains("3"), "the process tree says how many")
    }
}

/**
 * A logger that keeps what it was told.
 *
 * Its own writer, so the test reads only its own lines and leaves the application's
 * logging configuration alone.
 */
private class CapturedLog {

    /** Each line as `tag: message`. */
    val lines = mutableListOf<String>()

    /** A logger that writes into this capture. */
    fun logger(tag: String = "acp-session"): Logger {
        val writer = object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                lines.add("$tag: $message")
            }
        }
        return Logger(mutableLoggerConfigInit(writer), tag)
    }
}
