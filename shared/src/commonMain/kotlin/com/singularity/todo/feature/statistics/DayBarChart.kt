@file:Suppress("FunctionSignature")

package com.singularity.todo.feature.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One part of a bar, painted upward from the base. */
internal data class BarSegment(val value: Float, val color: Color)

/** One bar: what it stands for, and the day number printed under it. */
internal data class DayBar(val label: String, val segments: List<BarSegment>)

/** The widest gap the chart will spend between two bars. */
private val MAX_BAR_SPACING = 8.dp

/**
 * The one bar chart the statistics draw: [bars] spread across the width they
 * are given, each day number printed under the bar it belongs to.
 *
 * ## Why this is one widget
 *
 * The Tasks and Time tabs used to draw their own chart, each with its own fixed
 * gap between bars and its own way of placing the day numbers. Both went wrong
 * the same way at a range longer than a week: with a fixed 8 dp gap thirty bars
 * became one-pixel slivers, and ninety packed the width away entirely. Sharing
 * [barChartGeometry] and the label row puts the rule in one place, so the bars
 * and their numbers cannot disagree about where a day sits.
 *
 * Labels are drawn as [Text] nodes rather than painted into the canvas, so they
 * stay readable to a screen reader, and they are centred over their bar even
 * when the bar is narrower than the number: the label is allowed to overflow
 * its slot rather than being squeezed into it. Only [MAX_DAY_LABELS] are
 * printed — see [labelStride] — counted back from the newest day.
 *
 * @param bars Oldest first, so the newest day is the rightmost bar.
 * @param chartHeight The height of the bars themselves; the labels sit below.
 */
@Composable
internal fun DayBarChart(
    bars: List<DayBar>,
    chartHeight: Dp,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val geometry = barChartGeometry(maxWidth.value, bars.size, MAX_BAR_SPACING.value)
        val spacing = geometry.spacing.dp
        val scale = stackScale(bars.map { bar -> bar.segments.map { it.value } })

        Column {
            Canvas(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(chartHeight),
            ) {
                // A bar is scaled against the busiest *stack*, not the tallest
                // single segment: overdue and completed share one bar, so the
                // sum is what has to fit. Scaling the segments separately was
                // the clipping bug — the busiest day's amber segment started
                // above the canvas and was never drawn.
                if (scale <= 0f) return@Canvas
                val barWidthPx = geometry.barWidth.dp.toPx()
                if (barWidthPx <= 0f) return@Canvas
                val spacingPx = spacing.toPx()

                bars.forEachIndexed { index, bar ->
                    val left = index * (barWidthPx + spacingPx)
                    val heights = stackHeights(bar.segments.map { it.value }, scale, size.height)
                    var bottom = size.height
                    bar.segments.forEachIndexed { segmentIndex, segment ->
                        val height = heights[segmentIndex]
                        if (height > 0f) {
                            drawRect(
                                color = segment.color,
                                topLeft = Offset(left, bottom - height),
                                size = Size(barWidthPx, height),
                            )
                            bottom -= height
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            DayLabels(bars = bars, spacing = spacing)
        }
    }
}

/**
 * The day numbers under the bars, on the bars' own grid.
 *
 * A label sits in its bar's slot and is allowed to overflow it: at 90 days a
 * slot is a few dp, and a number squeezed into it would be unreadable. Only
 * every [labelStride]th day is printed, counted back from the newest, and the
 * skipped slots keep a spacer so the printed ones stay under their bar.
 */
@Composable
private fun DayLabels(
    bars: List<DayBar>,
    spacing: Dp,
) {
    val stride = labelStride(bars.size)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        bars.forEachIndexed { index, bar ->
            if (hasDayLabel(index, bars.size, stride)) {
                Text(
                    text = bar.label,
                    modifier =
                        Modifier
                            .weight(1f)
                            .wrapContentWidth(Alignment.CenterHorizontally, unbounded = true),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}
