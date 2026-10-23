package com.singularity.todo.mcp

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Standard output is the protocol channel, and nothing else.
 *
 * ## What this protects
 *
 * A stdio MCP client reads the server's standard output and parses every line as
 * JSON. The specification says the server must not write anything there that is
 * not a protocol message, and a client that takes that literally stops at the
 * first line it cannot parse.
 *
 * The server used to break that rule in two ways at once: its own log lines, and
 * a start-up banner from a third-party logging library, both landed on standard
 * output. Nothing in the test suite could see it, because the tests speak to the
 * server in-process rather than over a pipe.
 *
 * So the assertion here is the one a client would make: after the reservation,
 * a plain `println` does not appear on standard output.
 *
 * ## Why the class is isolated
 *
 * The thing under test is a process-global: `System.out` and `System.err` are
 * replaced for the duration of the assertion and put back afterwards. The test
 * suite runs classes and methods concurrently, so a test that changes the
 * standard streams without saying so would race every other test in the JVM —
 * and would fail at random rather than for a reason. [Isolated] keeps the class
 * alone in its JVM, and [Execution] keeps its two methods apart.
 */
@Tag("fast")
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class ProtocolStdoutTest {

    @Test
    fun `the reserved stream is the one standard output was before the reservation`() {
        val realOut = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true))
        try {
            val before = System.out
            val reserved = ProtocolStdout.reserve()

            assertSame(
                before,
                reserved,
                "The protocol must be written to the original standard output, " +
                    "not to the stream everything else was moved to.",
            )
        } finally {
            System.setOut(realOut)
        }
    }

    @Test
    fun `a print after the reservation goes to standard error`() {
        val realOut = System.out
        val realErr = System.err
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(err, true))
        try {
            ProtocolStdout.reserve()

            println("this is not a protocol message")

            assertEquals(
                "",
                out.toString(),
                "A print must not reach the protocol channel: a client parses every " +
                    "line of standard output as a message.",
            )
            assertTrue(
                err.toString().contains("this is not a protocol message"),
                "Everything that is not the protocol must reach standard error.",
            )
        } finally {
            System.setOut(realOut)
            System.setErr(realErr)
        }
    }
}
