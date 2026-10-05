package com.singularity.todo.core.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Colours that draw DATA, not chrome.
 *
 * A categorical series palette is neither a theme role nor a semantic verdict: it
 * exists so six series are tellable apart. Deriving it from the colour scheme —
 * or from the accent — would make every series the same hue and the chart
 * useless, so this palette is fixed and deliberately does not respond to the
 * theme.
 *
 * The semantic bars below it *are* verdicts (completed, overdue), and for the
 * same reason they stay fixed: how urgent something looks must not change when
 * the user changes a colour preference.
 *
 * Six hues, checked for mutual distinguishability rather than for prettiness, and
 * distinguishable in greyscale too — the completed and overdue bars must not
 * collapse into each other for a user who cannot separate green from amber.
 */

/** Status colours for bars and counters that describe task state. */
object DataStatusColors {
    val Completed = Color(0xFF4CAF50)
    val Overdue = Color(0xFFFF9800)
}

/**
 * Categorical palette for chart series.
 *
 * Fixed order, so a series keeps its colour between renders — a chart that
 * reassigns hues on every recomposition is unreadable at a glance.
 */
object DataSeriesColors {
    val Green = Color(0xFF4CAF50)
    val Blue = Color(0xFF2196F3)
    val Orange = Color(0xFFFF9800)
    val Purple = Color(0xFF9C27B0)
    val Pink = Color(0xFFE91E63)
    val Cyan = Color(0xFF00BCD4)

    /** Indexed by series position, wrapping rather than failing on a seventh series. */
    val ordered: List<Color> = listOf(Green, Blue, Orange, Purple, Pink, Cyan)

    fun at(index: Int): Color = ordered[index % ordered.size]
}
