package com.singularity.todo.core.process

import java.io.IOException
import com.singularity.todo.test.helpers.awaitState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The duplex runner, exercised against **real** child processes.
 *
 * ## Why this test is not like the others
 *
 * Every other test of the agent machinery injects a double in place of the process.
 * That is right for asserting *what* was launched, and it is exactly why a defect in
 * the real runner can pass the whole suite: the double is what the tests see.
 *
 * So this test runs actual commands, and it checks the three things a double cannot:
 * that a child writing more than a pipe buffer holds does not deadlock, that the
 * child's standard error never lands in the protocol stream, and that a missing
 * binary is reported rather than swallowed.
 *
 * ## Why the third one matters most
 *
 * A protocol stream is a byte stream with a grammar. A diagnostic folded into it is a
 * parse error waiting for a frame that happens to contain a newline — and it arrives
 * at the worst possible moment, in the middle of a frame the application is reading.
 */
@Tag("slow")
@Tag("slow:platform")
class SubprocessDuplexTest {

    @Test
    fun `a child that writes more than a pipe buffer holds does not deadlock`() = runTest {
        val process = Subprocess.spawnDuplex(listOf("sh", "-c", "seq 1 50000"))

        val lines = process.stdoutLines().toList()

        assertEquals(50_000, lines.size, "every line arrived: the child was never blocked on a full pipe")
        process.close()
    }

    @Test
    fun `standard error goes to its own sink and never into the protocol stream`() = runTest {
        val diagnostics = mutableListOf<String>()
        val process = Subprocess.spawnDuplex(
            argv = listOf("sh", "-c", "echo to-stdout; echo to-stderr 1>&2"),
            stderrSink = { diagnostics += it },
        )

        val lines = process.stdoutLines().toList()

        assertEquals(listOf("to-stdout"), lines, "the protocol stream carries the protocol and nothing else")
        // The drain is a thread of its own, so the line arrives in its own time.
        awaitState { diagnostics.isNotEmpty() }
        assertEquals(listOf("to-stderr"), diagnostics, "the diagnostic reached the sink")
        process.close()
    }

    @Test
    fun `a clean end of output ends the stream`() = runTest {
        val process = Subprocess.spawnDuplex(listOf("sh", "-c", "echo one; echo two"))

        val lines = process.stdoutLines().toList()

        assertEquals(listOf("one", "two"), lines, "the stream ended by itself")
        process.close()
    }

    @Test
    fun `a line written to the child comes back`() = runTest {
        val process = Subprocess.spawnDuplex(listOf("sh", "-c", "read line; echo \"got \$line\""))

        process.writeLine("hello")

        val lines = process.stdoutLines().toList()
        assertEquals(listOf("got hello"), lines, "the child read the line and answered it")
        process.close()
    }

    @Test
    fun `a missing binary is reported rather than swallowed`() {
        assertFailsWith<IOException> {
            Subprocess.spawnDuplex(listOf("/nonexistent/definitely-not-a-binary"))
        }
    }

    @Test
    fun `the child exits and its code is reported`() {
        val process = Subprocess.spawnDuplex(listOf("sh", "-c", "exit 3"))

        val code = process.awaitExit(timeoutMillis = 10_000)

        assertEquals(3, code, "the child's exit code is what the caller asked about")
        process.close()
    }

    @Test
    fun `closing twice is not an error`() {
        val process = Subprocess.spawnDuplex(listOf("sh", "-c", "sleep 1"))

        process.close()
        process.close()

        assertTrue(true, "the second close did nothing and threw nothing")
    }
}
