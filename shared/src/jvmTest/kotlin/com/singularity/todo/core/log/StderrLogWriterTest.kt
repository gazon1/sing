package com.singularity.todo.core.log

import co.touchlab.kermit.Severity
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The log goes to standard error, because standard output is the protocol.
 *
 * ## What this protects
 *
 * The tool server's standard output is the MCP channel: a client parses every line
 * there as a message. The server's own log lines once landed there too, and a client
 * that parses strictly stops at the first line it cannot parse — so the agent is left
 * without the tool access the application promised it, and nothing on the screen says
 * why.
 *
 * The server used to get the log off the channel by accident: a global redirect moved
 * `System.out` to standard error, and the log followed it there. That made the one
 * thing this repository controls depend on an arrangement nobody could see in the
 * logging code.
 *
 * So the placement is asserted on its own: the writer is told where to write, and it
 * writes there with no redirect in place at all.
 *
 * ## Why the class is isolated
 *
 * `System.err` is replaced for the duration of the assertion and put back afterwards.
 * The suite runs classes and methods concurrently, so a test that changes the
 * standard streams without saying so would race every other test in the JVM — and
 * would fail at random rather than for a reason.
 */
@Tag("fast")
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class StderrLogWriterTest {

    @Test
    fun `a log line reaches standard error and nothing reaches standard output`() {
        val realOut = System.out
        val realErr = System.err
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(err, true))
        try {
            // No reservation, no redirect: the writer's own configuration is the
            // only thing that decides where the log goes.
            StderrLogWriter().log(
                severity = Severity.Error,
                message = "a log line that is not a protocol message",
                tag = "Test",
                throwable = null,
            )

            assertEquals(
                "",
                out.toString(),
                "The log must not reach the protocol channel.",
            )
            assertTrue(
                err.toString().contains("a log line that is not a protocol message"),
                "The log must reach standard error, where the protocol does not live.",
            )
            assertTrue(
                err.toString().contains("Test"),
                "The tag belongs on the line: a reader of the log has to know who said it.",
            )
        } finally {
            System.setOut(realOut)
            System.setErr(realErr)
        }
    }
}
