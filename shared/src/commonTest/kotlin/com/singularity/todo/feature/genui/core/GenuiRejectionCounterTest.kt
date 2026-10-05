package com.singularity.todo.feature.genui.core

import com.singularity.todo.feature.genui.surface.SurfaceId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The instrument that answers "which mistake does the model actually make".
 *
 * It exists because everything the layer learned about rejections went to a log line, and a log
 * line cannot be counted after the fact. Without a tally, the choice between a better prompt, a
 * looser catalog and a different component set is made by impression.
 */
@Tag("fast")
class GenuiRejectionCounterTest {

    private val counter = GenuiRejectionCounter()

    @Test
    fun nothingIsRecordedForACleanAnswer() {
        counter.record(emptyList())
        assertTrue(counter.counts.value.isEmpty())
        assertEquals("", counter.snapshot())
    }

    @Test
    fun eachCodeIsCountedSeparatelyAndRepeatedly() {
        counter.record(listOf(unknown("chart"), unknown("gauge")))
        counter.record(listOf(unknown("chart")))

        assertEquals(3, counter.counts.value.getValue(A2uiErrorCode.UNKNOWN_COMPONENT))
        assertEquals(1, counter.counts.value.size, "Only the code is tallied, not the component")
    }

    @Test
    fun theSnapshotIsOrderedByHowOftenItHappened() {
        counter.record(listOf(unknown("chart")))
        counter.record(listOf(missing(), missing(), missing()))

        assertEquals("MISSING_PROPERTY=3, UNKNOWN_COMPONENT=1", counter.snapshot())
    }

    @Test
    fun theSnapshotOfNothingIsEmptyRatherThanAWord() {
        // Rendered into a log line next to the turn; an empty map must not print as "{}" or "null".
        assertEquals("", counter.snapshot())
    }

    @Test
    fun resetForgetsEverything() {
        counter.record(listOf(unknown("chart")))
        counter.reset()
        assertTrue(counter.counts.value.isEmpty())
    }

    @Test
    fun recordingAndReportingReturnsTheRunningDistribution() {
        counter.recordAndReport(listOf(unknown("chart")))
        val running: String = counter.recordAndReport(listOf(missing()))

        assertEquals("UNKNOWN_COMPONENT=1, MISSING_PROPERTY=1", running)
    }

    private fun unknown(kind: String): A2uiError = A2uiError(
        code = A2uiErrorCode.UNKNOWN_COMPONENT,
        message = "No component '$kind'",
        componentId = kind,
        allowed = listOf("text"),
    )

    private fun missing(): A2uiError = A2uiError(
        code = A2uiErrorCode.MISSING_PROPERTY,
        message = "A required property is absent",
        surfaceId = SurfaceId("s1"),
    )
}
