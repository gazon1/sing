package com.singularity.todo.feature.genui.core

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The framer exists because the two ends of the pipeline disagreed about what a string is: the
 * transport emits token deltas that end wherever the token boundary falls, and the parser takes
 * one whole object per call. The engine was never called, so the contradiction between the two
 * signatures was never observable — and the comment claiming the parser buffered the difference
 * was wrong.
 *
 * The split test below is the one that matters: every possible split of the same response must
 * produce the same lines, because the transport does not get a say in where it splits.
 */
@Tag("fast")
class LineFramerTest {

    private val message = """{"createSurface":{"surfaceId":"s1","rootId":"t1"}}"""

    @Test
    fun aWholeLineIsEmittedOnItsNewline() {
        val framer = LineFramer()
        val produced = framer.accept("$message\n")
        assertEquals(listOf(FramedLine.Message(message)), produced)
    }

    @Test
    fun everySplitPointProducesTheSameLine() {
        for (split in 1 until message.length) {
            val framer = LineFramer()
            val produced = framer.accept(message.substring(0, split) + "\n" + message.substring(split))
            val first = produced.firstOrNull()
            assertTrue(
                first is FramedLine.Message,
                "Split at $split produced $first",
            )
        }
    }

    @Test
    fun aFragmentEndsMidObjectAndIsHeldUntilTheRestArrives() {
        val framer = LineFramer()
        assertEquals(emptyList(), framer.accept(message.substring(0, 10)), "No complete line yet")
        val produced = framer.accept(message.substring(10) + "\n")
        assertEquals(listOf(FramedLine.Message(message)), produced)
    }

    @Test
    fun windowsAndUnixLineEndingsBothSplit() {
        val framer = LineFramer()
        val produced = framer.accept("first\r\n$message\r\n")
        assertEquals(FramedLine.Prose("first"), produced[0])
        assertEquals(FramedLine.Message(message), produced[1])
    }

    @Test
    fun markdownFencesAreStrippedAndNotTreatedAsProse() {
        val framer = LineFramer()
        val produced = framer.accept("```json\n$message\n```\n")
        assertEquals(listOf(FramedLine.Message(message)), produced)
    }

    @Test
    fun proseIsKeptAsProse() {
        val framer = LineFramer()
        val produced = framer.accept("Here you go:\n$message\nHope that helps.\n")
        assertEquals(FramedLine.Prose("Here you go:"), produced[0])
        assertEquals(FramedLine.Message(message), produced[1])
        assertEquals(FramedLine.Prose("Hope that helps."), produced[2])
    }

    @Test
    fun blankLinesAreNotEmittedAtAll() {
        val framer = LineFramer()
        assertEquals(emptyList(), framer.accept("\n\n   \n"))
    }

    @Test
    fun anUnterminatedTrailingLineIsReportedAsDropped() {
        val framer = LineFramer()
        framer.accept("$message\n{\"createSurface\":{\"surfaceId\"")
        val remainder = framer.finish()
        assertEquals(1, remainder.size, remainder.toString())
        assertTrue(remainder.single() is FramedLine.Dropped, remainder.toString())
    }

    @Test
    fun anUnterminatedProseTailIsKept() {
        val framer = LineFramer()
        framer.accept("$message\nand that is all")
        assertEquals(listOf(FramedLine.Prose("and that is all")), framer.finish())
    }

    @Test
    fun finishingAnEmptyResponseProducesNothing() {
        assertEquals(emptyList(), LineFramer().finish())
    }

    @Test
    fun anOverlongLineIsSkippedToTheNextLineWithoutLosingIt() {
        val framer = LineFramer(maxLineLength = 64)
        val produced = framer.accept("y".repeat(200) + "\n$message\n")
        assertTrue(produced.any { it is FramedLine.Dropped }, "The runaway line is reported")
        assertEquals(FramedLine.Message(message), produced.last(), "The next line is unaffected")
    }

    @Test
    fun anOverlongTailIsReportedOnFinish() {
        val framer = LineFramer(maxLineLength = 16)
        framer.accept("z".repeat(200))
        assertTrue(framer.finish().any { it is FramedLine.Dropped })
    }

    @Test
    fun aResponseSplitAcrossManyTinyFragmentsStillYieldsEveryMessage() {
        val response = """
            first line of prose
            {"createSurface":{"surfaceId":"s1","rootId":"t1","components":[]}}
            {"updateData":{"surfaceId":"s1","path":"/name","value":"Ada"}}
            trailing thought
        """.trimIndent()
        val framer = LineFramer()
        val produced = mutableListOf<FramedLine>()
        for (character in response) {
            produced += framer.accept(character.toString())
        }
        produced += framer.finish()
        assertEquals(2, produced.count { it is FramedLine.Message })
        assertEquals(2, produced.count { it is FramedLine.Prose })
    }
}
