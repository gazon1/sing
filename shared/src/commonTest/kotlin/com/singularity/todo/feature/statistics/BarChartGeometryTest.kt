package com.singularity.todo.feature.statistics

import org.junit.jupiter.api.Tag
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rule the charts got wrong: the bars have to fit the width they are given,
 * whatever range the user selected, and the day labels have to stay on the same
 * grid as the bars.
 *
 * The width below is a 360 dp phone: 16 dp of screen padding each side and 16 dp
 * of card padding each side, which is the narrowest place these charts are drawn.
 */
@Tag("fast")
class BarChartGeometryTest {
    private val phoneChartWidth = 296f
    private val maxSpacing = 8f

    private fun paintedWidth(barCount: Int): Float {
        val geometry = barChartGeometry(phoneChartWidth, barCount, maxSpacing)
        return barCount * geometry.barWidth + (barCount - 1) * geometry.spacing
    }

    @Test
    fun `seven days keep the wide gap and a wide bar`() {
        val geometry = barChartGeometry(phoneChartWidth, 7, maxSpacing)

        assertEquals(maxSpacing, geometry.spacing, "a week is roomy enough for the full gap")
        assertTrue(geometry.barWidth > 30f, "seven bars should stay wide, got $geometry")
    }

    @Test
    fun `thirty days stay readable instead of collapsing to slivers`() {
        val geometry = barChartGeometry(phoneChartWidth, 30, maxSpacing)

        assertTrue(geometry.barWidth >= 6f, "30 bars must stay visible, got $geometry")
        assertTrue(geometry.spacing < maxSpacing, "the gap must give way to the bars, got $geometry")
    }

    @Test
    fun `ninety days still get a bar rather than nothing`() {
        val geometry = barChartGeometry(phoneChartWidth, 90, maxSpacing)

        assertTrue(geometry.barWidth > 0f, "90 bars used to go negative and draw nothing, got $geometry")
        assertTrue(geometry.spacing > 0f)
    }

    @Test
    fun `every bar count fits the width it was given`() {
        for (barCount in 1..400) {
            val geometry = barChartGeometry(phoneChartWidth, barCount, maxSpacing)

            assertTrue(geometry.barWidth > 0f, "$barCount bars vanished")
            // The bars and their gaps fill the width exactly — the same
            // division a row of evenly weighted label slots makes, which is
            // what keeps each label over its bar.
            assertTrue(
                abs(paintedWidth(barCount) - phoneChartWidth) < 0.01f,
                "$barCount bars painted ${paintedWidth(barCount)} of $phoneChartWidth",
            )
        }
    }

    @Test
    fun `a chart with no width or no bars has no geometry`() {
        assertEquals(BarChartGeometry(0f, 0f), barChartGeometry(0f, 30, maxSpacing))
        assertEquals(BarChartGeometry(0f, 0f), barChartGeometry(phoneChartWidth, 0, maxSpacing))
    }

    @Test
    fun `the labels step so that a chart prints at most seven of them`() {
        assertEquals(1, labelStride(7))
        assertEquals(5, labelStride(30))
        assertEquals(13, labelStride(90))
    }

    @Test
    fun `the newest bar is always labelled`() {
        for (barCount in 1..90) {
            assertTrue(
                hasDayLabel(barCount - 1, barCount, labelStride(barCount)),
                "$barCount days: the newest day lost its label",
            )
        }
    }

    @Test
    fun `no range prints more labels than a reader can take in`() {
        for (barCount in 1..90) {
            val stride = labelStride(barCount)
            val printed = (0 until barCount).count { hasDayLabel(it, barCount, stride) }

            assertTrue(printed in 1..MAX_DAY_LABELS, "$barCount days printed $printed labels")
        }
    }

    @Test
    fun `the printed labels are evenly spaced back from the newest day`() {
        val barCount = 30
        val stride = labelStride(barCount)
        val labels = (0 until barCount).filter { hasDayLabel(it, barCount, stride) }

        assertEquals(listOf(4, 9, 14, 19, 24, 29), labels)
    }

    @Test
    fun `a bar is scaled against its stack, so the busiest day still fits`() {
        // Overdue first (drawn from the base), completed above it.
        val busiest = listOf(3f, 2f)
        val other = listOf(0f, 3f)
        val scale = stackScale(listOf(busiest, other))

        assertEquals(5f, scale, "the scale is the busiest stack, not the tallest segment")
        assertEquals(
            200f,
            stackHeights(busiest, scale, height = 200f).sum(),
            "the busiest bar fills the chart",
        )
    }

    @Test
    fun `no segment is ever drawn above the canvas`() {
        val days = listOf(listOf(3f, 3f), listOf(0f, 5f), listOf(1f, 0f))
        val scale = stackScale(days)

        for (day in days) {
            val heights = stackHeights(day, scale, height = 200f)

            assertTrue(heights.all { it >= 0f }, "$day produced a negative height")
            assertTrue(heights.sum() <= 200f, "$day overran the chart: ${heights.sum()}")
        }
    }

    @Test
    fun `a chart with nothing recorded has no scale`() {
        assertEquals(0f, stackScale(listOf(listOf(0f, 0f))))
        assertEquals(listOf(0f, 0f), stackHeights(listOf(1f, 1f), scale = 0f, height = 200f))
    }
}
