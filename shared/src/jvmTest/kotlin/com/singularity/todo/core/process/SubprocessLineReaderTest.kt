package com.singularity.todo.core.process

import java.io.StringReader
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The line reader, on its own.
 *
 * The duplex runner reads the child's output through this reader, and the line
 * that matters most is the last one: the child ended its output without a
 * terminator, so that line carries no newline of its own. A reader that stops
 * at the end of the stream instead of falling through to the line it has
 * buffered drops it — and a dropped last line is a protocol frame that never
 * arrives.
 *
 * ## Why this is not a slow test
 *
 * The case was reachable only through a spawned child process, and a test that
 * needs a spawn belongs to the slow population — which the pull request does
 * not run. A regression there was invisible until the nightly. The reader is
 * `internal` now, and the case is fast.
 */
@Tag("fast")
class SubprocessLineReaderTest {

    @Test
    fun `a last line without a newline is still delivered`() {
        val lines = readCappedLines(StringReader("first\nsecond"), MAX_LINE).toList()

        assertEquals(
            listOf("first", "second"),
            lines,
            "the last line arrived even though the child never terminated it",
        )
    }

    @Test
    fun `a terminated last line is delivered once, with no empty line after it`() {
        val lines = readCappedLines(StringReader("first\nsecond\n"), MAX_LINE).toList()

        assertEquals(listOf("first", "second"), lines, "the terminator does not make an extra line")
    }

    @Test
    fun `output with no newline at all is one line`() {
        val lines = readCappedLines(StringReader("only"), MAX_LINE).toList()

        assertEquals(listOf("only"), lines, "the whole output is the last line")
    }

    @Test
    fun `empty output yields no lines`() {
        val lines = readCappedLines(StringReader(""), MAX_LINE).toList()

        assertEquals(emptyList(), lines, "nothing in, nothing out")
    }

    private companion object {
        /** The cap the runner reads with — one mebibyte. */
        const val MAX_LINE = 1_048_576
    }
}
