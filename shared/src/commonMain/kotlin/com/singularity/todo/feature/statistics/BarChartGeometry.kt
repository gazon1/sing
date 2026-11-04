package com.singularity.todo.feature.statistics

/**
 * How a row of bars divides the width it was given.
 *
 * @param barWidth What one bar may paint.
 * @param spacing The gap between one bar's right edge and the next bar's left
 *   edge.
 */
internal data class BarChartGeometry(val barWidth: Float, val spacing: Float)

/**
 * Divides [width] among [barCount] bars without ever letting one vanish.
 *
 * The gap is a quarter of a bar's slot, capped at [maxSpacing]. One rule then
 * covers seven bars (a wide gap) and ninety (a hairline), instead of a fixed
 * gap being spent out of the width the bars themselves need: at 30 days a fixed
 * 8 dp left ~3 dp bars on a phone, and at 90 the remaining width went negative
 * and nothing was drawn at all.
 *
 * The gap is subtracted from the width before the bars divide it — exactly the
 * way a row of evenly weighted children with a space between them divides it.
 * That is what keeps a printed day label over its bar: the label row and the
 * bars agree by construction, not by two formulas that happen to be close.
 */
internal fun barChartGeometry(width: Float, barCount: Int, maxSpacing: Float): BarChartGeometry {
    if (barCount <= 0 || width <= 0f) return BarChartGeometry(0f, 0f)
    val slot = width / barCount
    val spacing = (slot * GAP_FRACTION).coerceAtMost(maxSpacing).coerceAtLeast(0f)
    val barWidth = (width - (barCount - 1) * spacing) / barCount
    return BarChartGeometry(barWidth = barWidth.coerceAtLeast(0f), spacing = spacing)
}

/** The share of a bar's slot spent on the gap to its neighbour. */
private const val GAP_FRACTION = 0.25f

/** How many day labels a chart may print before they stop being readable. */
internal const val MAX_DAY_LABELS = 7

/**
 * How many bars there are between two printed day labels.
 *
 * Labels are counted back from the newest bar ([hasDayLabel]), so the newest
 * day is always labelled and the printed set does not shift as days land.
 */
internal fun labelStride(barCount: Int, maxLabels: Int = MAX_DAY_LABELS): Int =
    if (maxLabels <= 0 || barCount <= maxLabels) 1 else (barCount + maxLabels - 1) / maxLabels

/** Whether bar [index] of [barCount] gets a printed day label. */
internal fun hasDayLabel(index: Int, barCount: Int, stride: Int): Boolean =
    stride <= 1 || (index in 0 until barCount && (barCount - 1 - index) % stride == 0)

/**
 * The value one bar's full height stands for: the busiest *stack* in the chart.
 *
 * Segments share a bar, so the scale is the largest sum, not the largest single
 * segment. Scaling each segment against its own kind was the clipping bug: on a
 * day that had both the most completed tasks and an overdue task, the amber
 * segment was placed above the canvas and never drawn.
 */
internal fun stackScale(bars: List<List<Float>>): Float =
    bars.maxOfOrNull { stack -> stack.sum() } ?: 0f

/**
 * The painted height of each segment of one bar, in the order given.
 *
 * No height is negative and the heights together never exceed [height], so a
 * bar can always be drawn from the base upward without leaving the canvas.
 */
internal fun stackHeights(values: List<Float>, scale: Float, height: Float): List<Float> =
    if (scale <= 0f || height <= 0f) {
        values.map { 0f }
    } else {
        values.map { value -> ((value / scale).coerceIn(0f, 1f) * height) }
    }
